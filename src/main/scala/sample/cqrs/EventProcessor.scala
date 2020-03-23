package sample.cqrs

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.typed.PersistenceId
import akka.stream.KillSwitches
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Subscriber
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.immutable

/**
 * General purpose event processor infrastructure.
 */
object EventProcessor {

  case object Ping extends CborSerializable

  def entityKey(eventProcessorId: String): EntityTypeKey[Ping.type] = EntityTypeKey[Ping.type](eventProcessorId)

  def init[Event](
      system: ActorSystem[_],
      settings: EventProcessorSettings,
      eventProcessorStream: String => EventProcessorStream[Event]): Unit = {
    val eventProcessorEntityKey = entityKey(settings.id)

    ClusterSharding(system).init(Entity(eventProcessorEntityKey)(entityContext =>
      EventProcessor(eventProcessorStream(entityContext.entityId))).withRole("read-model"))

    KeepAlive.init(system, eventProcessorEntityKey)
  }

  def apply(eventProcessorStream: EventProcessorStream[_]): Behavior[Ping.type] = {

    Behaviors.setup { context =>
      val killSwitch = KillSwitches.shared("eventProcessorSwitch")
      eventProcessorStream.runQueryStream(killSwitch)

      Behaviors
        .receiveMessage[Ping.type] { ping =>
          Behaviors.same
        }
        .receiveSignal {
          case (_, PostStop) =>
            killSwitch.shutdown()
            Behaviors.same
        }

    }
  }

}

abstract class EventProcessorStream[Event: ClassTag](
    system: ActorSystem[_],
    executionContext: ExecutionContext,
    eventProcessorId: String,
    tag: String) {

  protected val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val sys: ActorSystem[_] = system
  implicit val ec: ExecutionContext = executionContext

  private val query =
    PersistenceQuery(system.toClassic).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  protected def processEvent(event: Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done]

  def runQueryStream(killSwitch: SharedKillSwitch): Unit = {
    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
            processEventsByTag()
      }
      .via(killSwitch.flow)
      .runWith(Sink.ignore)
  }

  private def processEventsByTag(): Source[Offset, NotUsed] = {
    query.eventsByTag(tag, Offset.noOffset).mapAsync(1) { eventEnvelope =>
      eventEnvelope.event match {
        case event: Event =>
          processEvent(event, PersistenceId.ofUniqueId(eventEnvelope.persistenceId), eventEnvelope.sequenceNr).map(_ =>
            eventEnvelope.offset)
        case other =>
          Future.failed(new IllegalArgumentException(s"Unexpected event [${other.getClass.getName}]"))
      }
    }
  }
}
