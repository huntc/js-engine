package akka.contrib.process

import akka.actor._
import akka.util.ByteString
import java.io._
import scala.collection.JavaConversions._
import java.lang.{ProcessBuilder => JdkProcessBuilder}
import akka.contrib.process.Process.Started
import akka.contrib.process.StreamEvents.{Done, Ack, Output}

/**
 * Process encapsulates an operating system process and its ability to be communicated with
 * via stdio i.e. stdin, stdout and stderr.
 */
class Process(args: Seq[String], receiver: ActorRef, blockingDispatcherId: String, detached: Boolean)
  extends Actor {

  val pb = new JdkProcessBuilder(args.toList)
  val p = pb.start()

  val stdinSink = context.actorOf(Sink.props(p.getOutputStream)(context.system).withDispatcher(blockingDispatcherId))
  val stdoutSource = context.actorOf(Source.props(p.getInputStream, receiver)(context.system).withDispatcher(blockingDispatcherId))
  val stderrSource = context.actorOf(Source.props(p.getErrorStream, receiver)(context.system).withDispatcher(blockingDispatcherId))

  context.watch(stdoutSource)
  context.watch(stderrSource)

  var stdoutTerminated, stderrTerminated = false

  def receive = {
    case Terminated(`stdoutSource`) =>
      stdoutTerminated = true
      if (stderrTerminated && !detached) context.stop(self)
    case Terminated(`stderrSource`) =>
      stderrTerminated = true
      if (stdoutTerminated && !detached) context.stop(self)
  }

  receiver ! Started(stdinSink, stdoutSource, stderrSource)

  override def postStop() {
    if (!detached) p.destroy()
  }
}

object Process {

  /**
   * Return the props required to create a process actor.
   * @param args The sequence of string arguments to pass to the process.
   * @param receiver The actor to receive output and error events.
   * @param blockingDispatcherId The dispatcher id to use for blocking operations.
   * @param detached Whether the process will be a daemon.
   * @param system The actor system to use.
   * @return a props object that can be used to create the process actor.
   */
  def props(
             args: Seq[String],
             receiver: ActorRef,
             blockingDispatcherId: String = "stdio-dispatcher",
             detached: Boolean = false
             )(implicit system: ActorSystem): Props = {
    Props(classOf[Process], args, receiver, blockingDispatcherId, detached)
  }

  /**
   * Sent on startup to the receiver - specifies the actors used for managing input, output and
   * error respectively.
   */
  case class Started(stdinSink: ActorRef, stdoutSource: ActorRef, stderrSource: ActorRef)

}

/**
 * Declares the types of event that are involved with streaming.
 */
object StreamEvents {

  /**
   * Sent in response to an Output even.
   */
  case object Ack

  /**
   * Sent when no more Output events are expected.
   */
  case object Done

  /**
   * An event conveying data.
   */
  case class Output(data: ByteString)

}

/**
 * A sink of data given an output stream. Flow control is implemented and for each Output event received an Ack
 * is sent in return. A Done event is expected when there is no more data to be written. On receiving a Done
 * event the associated output stream will be closed.
 */
class Sink(os: OutputStream, pipeSize: Int) extends Actor {
  def receive = {
    case Output(d) =>
      os.write(d.toArray)
      sender ! Ack
    case Done => context.stop(self)
  }

  override def postStop() {
    os.close()
  }
}

object Sink {
  def props(os: OutputStream, pipeSize: Int = 1024)(implicit system: ActorSystem): Props = {
    Props(classOf[Sink], os, pipeSize)
  }
}

/**
 * A source of data given an input stream. Flow control is implemented and for each Output event received by the receiver,
 * an Ack is expected in return. At the end of the source, a Done event will be sent to the receiver and its associated
 * input stream is closed.
 */
class Source(is: InputStream, receiver: ActorRef, pipeSize: Int) extends Actor {

  val buffer = new Array[Byte](pipeSize)

  def sendBuffer(len: Int): Unit = {
    if (len > -1) {
      receiver ! Output(ByteString.fromArray(buffer, 0, len))
    } else {
      receiver ! Done
      context.stop(self)
    }
  }

  def receive = {
    case Ack => sendBuffer(is.read(buffer))
  }

  sendBuffer(is.read(buffer))

  override def postStop() {
    is.close()
  }
}

object Source {
  def props(is: InputStream, receiver: ActorRef, pipeSize: Int = 1024)(implicit system: ActorSystem): Props = {
    Props(classOf[Source], is, receiver, pipeSize)
  }
}