package sample.cqrs

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId

import scala.concurrent.{ExecutionContext, Future}

class CashPoolEventProcessorStream(
    system: ActorSystem[_],
    executionContext: ExecutionContext,
    eventProcessorId: String,
    tag: String)
    extends EventProcessorStream[CashPool.Event](system, executionContext, eventProcessorId, tag) {

  def processEvent(event: CashPool.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    log.info("EventProcessor({}) consumed {} from {} with seqNr {}", tag, event, persistenceId, sequenceNr)
    system.eventStream ! EventStream.Publish(event)
    Future.successful(Done)
  }
}
