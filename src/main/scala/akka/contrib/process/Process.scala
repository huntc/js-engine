package akka.contrib.process

import akka.actor._
import scala.sys.process.{Process => ScalaProcess, ProcessIO => ScalaProcessIO}
import akka.util.{Timeout, ByteString}
import akka.contrib.process.Process._
import java.io._
import akka.contrib.process.Process.InputData
import akka.contrib.process.Process.OutputData
import akka.contrib.process.Process.ErrorData
import akka.pattern.ask
import scala.concurrent.duration._
import scala.util.{Try, Failure, Success}
import scala.concurrent.{Await, Promise}
import scala.annotation.tailrec

/**
 * Process encapsulates an operating system process and its ability to be communicated with
 * via stdio i.e. stdin, stdout and stderr.
 *
 * If the process requires stdin then it can be sent InputEvent objects with an EOF object
 * to signal that there are no more input events.
 *
 * Regular output and errors are communicated to a receiver object which is declared on
 * creating this actor. The last event is signalled by a "done" object. Flow control is also
 * provided in order to avoid performance bottlenecks.
 *
 * Bytestrings are used to minimise copying.
 *
 */
class Process(args: Seq[String], receiver: ActorRef, pipeSize: Int, daemonize: Boolean)
  extends Actor
  with ActorLogging {

  val stdinStreamPromise = Promise[OutputStream]()
  val maybeStdinStream = stdinStreamPromise.future

  /*
   * When we are informed of the output stream to write stdin then we complete a promise so that its
   * future yields the stream so we can write to it from other threads.
   */
  def receiveStdinStream(os: OutputStream): Unit = stdinStreamPromise.success(os)

  /*
   * Given an input stream, consume bytes into a byte string and communicate the events
   * to a receiving actor. We employ flow control also so that the receiver isn't
   * overwhelmed.
   */
  def sendEvents(is: InputStream, dataFactory: ByteString => Outbound, doneFactory: => Outbound): Unit = {

    implicit val timeout = Timeout(5.seconds)

    val buffer = new Array[Byte](pipeSize)

    def finishSending(): Unit = {
      is.close()
      receiver ! doneFactory
      self ! PoisonPill
    }

    @tailrec
    def sendWithFlowControl(len: Int): Unit = {
      if (len > -1) {
        // Unfortunately reading from an input stream can block. Such is the JDK.
        // It is important here that we await the reply as we must continue to
        // execute the stream IO on the thread given to us by the process library.
        val reply = receiver ? dataFactory(ByteString.fromArray(buffer, 0, len))
        Try(Await.result(reply, timeout.duration)) match {
          case Success(Ack) => sendWithFlowControl(is.read(buffer))

          case Success(x) =>
            log.error(s"Unknown message received while receiving output: $x")
            finishSending()
          case Failure(t) =>
            log.error(s"Failure while receiving output: $t")
            finishSending()
        }
      } else {
        finishSending()
      }
    }
    sendWithFlowControl(is.read(buffer))
  }

  val pio = new ScalaProcessIO(
    receiveStdinStream,
    sendEvents(_, OutputData, OutputDone),
    sendEvents(_, ErrorData, ErrorDone),
    daemonize
  )
  val p = ScalaProcess(args).run(pio)

  def withStdinStream(body: OutputStream => Unit): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    maybeStdinStream.onSuccess {
      case os => body(os)
    }
  }

  def receive = {
    case in: InputData =>
      withStdinStream {
        os =>
          os.write(in.data.toArray)
          sender ! Ack
      }
    case InputDone => withStdinStream(_.close())
  }

  override def postStop() {
    withStdinStream(_.close())
    if (!daemonize) p.destroy()
  }
}

object Process {

  /**
   * Return the props required to create a process actor.
   * @param args The sequence of string arguments to pass to the process.
   * @param receiver The actor to receive output and error events.
   * @param pipeSize The size of buffer used to store input, output and error data.
   * @param daemonize Whether the process will be a daemon.
   * @param system The actor system to use.
   * @return a props object that can be used to create the process actor.
   */
  def props(
             args: Seq[String],
             receiver: ActorRef,
             pipeSize: Int = 1024,
             daemonize: Boolean = false
             )(implicit system: ActorSystem): Props = {
    Props(classOf[Process], args, receiver, pipeSize, daemonize)
  }

  /**
   * Outbound constitutes those messages able to be emitted from the actor.
   */
  sealed trait Outbound

  /**
   * Sent from the outside for situations where a process demands input. Once all
   * input has been sent then InputDone must be sent. An Ack will be sent in reply to this
   * event and the sender should not send another input event until it receives it.
   * @param data the input data to send.
   */
  case class InputData(data: ByteString)

  case object InputDone

  /**
   * Sent to the receiver capturing regular output from the process. The receiver
   * must send an Ack when it is ready to receive the next event.
   * @param data the regular output.
   */
  case class OutputData(data: ByteString) extends Outbound

  case object OutputDone extends Outbound

  /**
   * Sent to the receiver capturing regular output from the process. The receiver
   * must send an Ack when it is ready to receive the next event.
   * @param data error output.
   */
  case class ErrorData(data: ByteString) extends Outbound

  case object ErrorDone extends Outbound

  /**
   * Sent in response to an event, acknowledging that it has been received.
   * The sender should ensure that no more events are sent until the ack is received.
   * The "Done" events do not require an Ack.
   */
  case object Ack

}