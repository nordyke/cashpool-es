package sample.cqrs

import scala.concurrent.Future

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.util.Timeout

object CashPoolRoutes {
  final case class Replenish(cashPoolId: BigInt, amount: BigDecimal)
  final case class DrawDown(cashPoolId: BigInt, amount: BigDecimal)
  final case class AdjustLimit(cashPoolId: BigInt, amount: BigDecimal)
}

class CashPoolRoutes()(implicit system: ActorSystem[_]) {

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("cashpool.askTimeout"))
  private val sharding = ClusterSharding(system)

  import CashPoolRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  import JsonFormats._

  val cashPool: Route =
    pathPrefix("cashpool") {
        concat(
          pathPrefix("replenish"){
            put {
              entity(as[Replenish]) { data =>
                val entityRef = sharding.entityRefFor(CashPool.EntityKey, data.cashPoolId.toString())
                val reply: Future[CashPool.Confirmation] =
                  entityRef.ask(CashPool.Replenish(data.cashPoolId, data.amount, _))
                onSuccess(reply) {
                  case CashPool.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case CashPool.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
              }
            }
          },
          pathPrefix("draw-down"){
            put {
              entity(as[DrawDown]) {
                data =>
                  val entityRef = sharding.entityRefFor(CashPool.EntityKey, data.cashPoolId.toString())
                  val reply: Future[CashPool.Confirmation] =
                    entityRef.ask(CashPool.DrawDown(data.cashPoolId, data.amount, _))
                  onSuccess(reply) {
                    case CashPool.Accepted(summary) =>
                      complete(StatusCodes.OK -> summary)
                    case CashPool.Rejected(reason) =>
                      complete(StatusCodes.BadRequest, reason)
                  }
              }
            }
          },
          pathPrefix("adjust-limit"){
            put {
              entity(as[AdjustLimit]) {
                data =>
                  val entityRef = sharding.entityRefFor(CashPool.EntityKey, data.cashPoolId.toString())
                  val reply: Future[CashPool.Confirmation] =
                    entityRef.ask(CashPool.AdjustLimit(data.cashPoolId, data.amount, _))
                  onSuccess(reply) {
                    case CashPool.Accepted(summary) =>
                      complete(StatusCodes.OK -> summary)
                    case CashPool.Rejected(reason) =>
                      complete(StatusCodes.BadRequest, reason)
                  }
              }
            }
          },
          pathPrefix(Segment) { cashPoolId =>
            concat(get {
              val entityRef = sharding.entityRefFor(CashPool.EntityKey, cashPoolId)
              onSuccess(entityRef.ask(CashPool.Get)) { summary =>
                complete(summary)
              }
            })
          })
    }

}

object JsonFormats {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._

  implicit val summaryFormat: RootJsonFormat[CashPool.Summary] = jsonFormat3(CashPool.Summary)
  implicit val replenishFormat: RootJsonFormat[CashPoolRoutes.Replenish] = jsonFormat2(CashPoolRoutes.Replenish)
  implicit val drawDownFormat: RootJsonFormat[CashPoolRoutes.DrawDown] = jsonFormat2( CashPoolRoutes.DrawDown)
  implicit val adjustLimitFormat: RootJsonFormat[CashPoolRoutes.AdjustLimit] = jsonFormat2( CashPoolRoutes.AdjustLimit)

}
