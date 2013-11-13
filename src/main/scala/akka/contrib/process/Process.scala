package akka.contrib.process

import akka.actor._
import scala.sys.process.{Process => ScalaProcess, ProcessIO => ScalaProcessIO}
import akka.util.{Timeout, ByteString}
import akka.contrib.process.Process._
import java.io._
import akka.contrib.process.Process.InputEvent
import akka.contrib.process.Process.OutputEvent
import akka.contrib.process.Process.ErrorEvent
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.concurrent.Promise

/**
 * Process encapsulates an operating system process and its ability to be communicated with
 * via stdio i.e. stdin, stdout and stderr.
 *
 * If the process requires stdin then it can be sent InputEvent objects with an EOF object
 * to signal that there are no more input events.
 *
 * Regular output and errors are communicated to a receiver object which is declared on
 * creating this actor. The last event is signalled by an EOF object. Flow control is also
 * provided in order to avoid performance bottlenecks.
 *
 * Bytestrings are used to minimise copying.
 *
 */
class Process(args: Seq[String], receiver: ActorRef, pipeSize: Int) extends Actor with ActorLogging {

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
  def sendEvent(is: InputStream, eventFactory: ByteString => IOEvent): Unit = {

    implicit val timeout = Timeout(5.seconds)

    val buffer = new Array[Byte](pipeSize)

    def finishSending(): Unit = {
      is.close()
      receiver ! EOF
      self ! PoisonPill
    }

    def sendWithFlowControl(len: Int): Unit = {
      if (len > -1) {
        receiver ? eventFactory(ByteString.fromArray(buffer, 0, len)) onComplete {
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

  val pio = new ScalaProcessIO(receiveStdinStream, sendEvent(_, OutputEvent), sendEvent(_, ErrorEvent))
  val p = ScalaProcess(args).run(pio)

  def receive = {
    case in: InputEvent =>
      maybeStdinStream.onSuccess {
        case os =>
          os.write(in.data.toArray)
          sender ! Ack
      }
    case EOF =>
      maybeStdinStream.onSuccess {
        case os => os.close()
      }
  }

  override def postStop() {
    maybeStdinStream.onSuccess {
      case os => os.close()
    }
    p.destroy()
  }
}

object Process {

  /**
   * Return the props required to create a process actor.
   * @param args The sequence of string arguments to pass to the process.
   * @param receiver The actor to receive output and error events.
   * @param pipeSize The size of buffer used to store input, output and error data.
   * @param system The actor system to use.
   * @return a props object that can be used to create the process actor.
   */
  def props(args: Seq[String], receiver: ActorRef, pipeSize: Int = 1024)(implicit system: ActorSystem): Props = {
    Props(classOf[Process], args, receiver, pipeSize)
  }

  /**
   * IOEvents constitute those able to be handled by the process actor. All IO events can be
   * sent more than once.
   */
  sealed abstract class IOEvent

  /**
   * Sent from the outside for situations where a process demands input. Once all
   * input has been sent then EOF must be sent. An Ack will be sent in reply to this
   * event and the sender should not send another input event until it receives it.
   * @param data the input data to send.
   */
  case class InputEvent(data: ByteString) extends IOEvent

  /**
   * Sent to the receiver capturing regular output from the process. The receiver
   * must send an Ack when it is ready to receive the next event.
   * @param data the regular output.
   */
  case class OutputEvent(data: ByteString) extends IOEvent

  /**
   * Sent to the receiver capturing regular output from the process. The receiver
   * must send an Ack when it is ready to receive the next event.
   * @param data error output.
   */
  case class ErrorEvent(data: ByteString) extends IOEvent

  /**
   * Always sent as the last event in a sequence of IOEvent transmissions. EOF does
   * not require an Ack.
   */
  case object EOF extends IOEvent

  /**
   * Sent in response to an event, acknowledging that it has been received.
   * The sender should ensure that no more events are sent until the ack is received.
   * EOF does not require an Ack.
   */
  case object Ack extends IOEvent

}