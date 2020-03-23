package sample.cqrs

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect

/**
 * This is an event sourced actor. It has a state, [[CashPool.State]], which
 * stores the current CashPool state.
 *
 * Event sourced actors are interacted with by sending them commands,
 * see classes implementing [[CashPool.Command]].
 *
 * Commands get translated to events, see classes implementing [[CashPool.Event]].
 * It's the events that get persisted by the entity. Each event will have an event handler
 * registered for it, and an event handler updates the current state based on the event.
 * This will be done when the event is first created, and it will also be done when the entity is
 * loaded from the database - each event will be replayed to recreate the state
 * of the entity.
 */
object CashPool {


  final case class State(amount: BigDecimal, outstanding: BigDecimal) extends CborSerializable {

    def available: BigDecimal = amount - outstanding

    def adjustLimit(a: BigDecimal): State = copy(amount = a)

    def replenish(a: BigDecimal): State = {
      if (outstanding - a <= 0) {
        copy (outstanding = 0.0)
      } else {
        copy(outstanding = outstanding - a)
      }
    }

    def drawDown(a: BigDecimal): State = copy(outstanding = outstanding + a)

    def toSummary: Summary = Summary(amount = amount, outstanding = outstanding, available = available)

  }

  object State {
    def empty: State = State(0, 0)
  }

  sealed trait Command extends CborSerializable

  final case class Replenish(cashPoolId: BigInt, amount: BigDecimal, replyTo: ActorRef[Confirmation]) extends Command

  final case class DrawDown(cashPoolId: BigInt, amount: BigDecimal, replyTo: ActorRef[Confirmation]) extends Command

  final case class AdjustLimit(cashPoolId: BigInt, amount: BigDecimal, replyTo: ActorRef[Confirmation]) extends Command

  final case class Get(replyTo: ActorRef[Summary]) extends Command

  final case class Summary(amount: BigDecimal, outstanding: BigDecimal, available: BigDecimal) extends CborSerializable

  sealed trait Confirmation extends CborSerializable

  final case class Accepted(summary: Summary) extends Confirmation

  final case class Rejected(reason: String) extends Confirmation

  sealed trait Event extends CborSerializable {
    def cashPoolId: BigInt
  }

  final case class Replenished(cashPoolId: BigInt, amount: BigDecimal) extends Event

  final case class DrawnDown(cashPoolId: BigInt, amount: BigDecimal) extends Event

  final case class LimitAdjusted(cashPoolId: BigInt, amount: BigDecimal) extends Event

  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("CashPool")

  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      CashPool(entityContext.entityId.toInt, Set(eventProcessorTag))
    }.withRole("write-model"))
  }

  def apply(cashPoolId: BigInt, eventProcessorTags: Set[String]): Behavior[Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        PersistenceId(EntityKey.name, cashPoolId.toString()),
        State.empty,
        (state, command) => openCashPool(cashPoolId, state, command),
        (state, event) => handleEvent(state, event))
      .withTagger(_ => eventProcessorTags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

    private def openCashPool(cashPoolId: BigInt, state: State, command: Command): ReplyEffect[Event, State] =
    command match {
      case Replenish(cashPoolId, amount, replyTo) =>
        Effect.persist(Replenished(cashPoolId, amount))
        .thenReply(replyTo)(updatedCashPool => Accepted(updatedCashPool.toSummary))
      case DrawDown(cashPoolId, amount, replyTo) =>
        Effect.persist(DrawnDown(cashPoolId, amount))
        .thenReply(replyTo)(updatedCashPool => Accepted(updatedCashPool.toSummary))
      case AdjustLimit(cashPoolId, amount, replyTo) =>
        Effect.persist(LimitAdjusted(cashPoolId, amount))
          .thenReply(replyTo)(updatedCashPool => Accepted(updatedCashPool.toSummary))
      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }

  private def handleEvent(state: State, event: Event): State ={
    event match {
      case Replenished(_, amount) => state.replenish(amount)
      case DrawnDown(_, amount) => state.drawDown(amount)
      case LimitAdjusted(_, amount) => state.adjustLimit(amount)
    }
  }

}
