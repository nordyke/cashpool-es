package sample.cqrs

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object Main {

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        startNode(port, httpPort)

      case None =>
        throw new IllegalArgumentException("port number required")
    }
  }

  def startNode(port: Int, httpPort: Int): Unit = {
    ActorSystem[Nothing](Guardian(), "CashPool", config(port, httpPort))
  }

  def config(port: Int, httpPort: Int): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $port
      cashpool.http.port = $httpPort
       """).withFallback(ConfigFactory.load())

}

object Guardian {
  def apply(): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      val system = context.system
      val settings = EventProcessorSettings(system)
      val httpPort = context.system.settings.config.getInt("cashpool.http.port")

      CashPool.init(system, settings)

      if (Cluster(system).selfMember.hasRole("read-model")) {
        EventProcessor.init(
          system,
          settings,
          tag => new CashPoolEventProcessorStream(system, system.executionContext, settings.id, tag))
      }

      val routes = new CashPoolRoutes()(context.system)
      new CashPoolServer(routes.cashPool, httpPort, context.system).start()

      Behaviors.empty
    }
  }
}
