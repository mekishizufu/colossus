package colossus

import testkit._
import core._

import akka.actor._
import akka.agent._
import akka.testkit.TestProbe

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.ByteString

import RawProtocol._

class ServerSpec extends ColossusSpec {

  def expectConnections(server: ServerRef, num: Int) {
    server.server ! Server.GetInfo
    expectMsg(50.milliseconds, Server.ServerInfo(num, ServerStatus.Bound))
  }

  val EchoServerConfig = ServerConfig(
    name = "test-server",
    settings = ServerSettings(
      port = TEST_PORT
    ),
    delegatorFactory = Delegator.basic(() => new EchoHandler)
  )

  "IOSystem" must {
    "startup and shutdown" in {
      val io = IOSystem("test", 2)
      io ! WorkerManager.ReadyCheck
      expectMsg(100.milliseconds, WorkerManager.WorkersNotReady)
      Thread.sleep(100) //if it takes longer than this we're in trouble
      io ! WorkerManager.ReadyCheck
      val probe = TestProbe()
      probe watch io.workerManager
      expectMsgClass(50.milliseconds, classOf[WorkerManager.WorkersReady])
      io.shutdown()
      probe.expectTerminated(io.workerManager)
    }

    "list all registered servers" in {
      withIOSystem { implicit io =>
        implicit val ec = io.actorSystem.dispatcher
        val server1 = Server.basic("echo1", TEST_PORT, () => new EchoHandler)
        waitForServer(server1)
        val server2 = Server.basic("echo2", TEST_PORT + 1, () => new EchoHandler)
        waitForServer(server2)
        val servers = Await.result(io.registeredServers, 200 milliseconds)
        servers must have length 2
        servers.map(_.name.toString) mustBe Seq("/echo1", "/echo2")

        server2.shutdown()
        Thread.sleep(100)

        val remainingServers = Await.result(io.registeredServers, 200 milliseconds)
        remainingServers must have length 1
        remainingServers.head.name.toString mustBe "/echo1"
      }
    }
  }

  "Server" must {
    "attach to a system and start" in {
      withIOSystem { implicit io =>
        val server = Server.basic("echo", TEST_PORT, () => new EchoHandler)
        waitForServer(server)
        val c = TestClient(io, TEST_PORT)
        val data = ByteString("hello world!")
        Await.result(c.send(data), 100.milliseconds) must equal(data)
      }
    }

    "shutting down system shuts down attached servers" in {
      implicit val io = IOSystem("test", 2)
      val probe = TestProbe()
      probe watch io.workerManager
      val server = Server.basic("echo", TEST_PORT, () => new EchoHandler)
      val probe2 = TestProbe()
      probe2 watch server.server
      Thread.sleep(100)
      io.shutdown()
      probe2.expectTerminated(server.server)
      probe.expectTerminated(io.workerManager)
    }

    "shutdown when it cannot bind to a port when a duration is supplied" in {
      withIOSystem { implicit io =>
        val existingServer = Server.basic("echo3", TEST_PORT, () => new EchoHandler)
        waitForServer(existingServer)
        val settings = ServerSettings(port = TEST_PORT, bindingAttemptDuration = PollingDuration(50 milliseconds, Some(1L)))
        val cfg = ServerConfig("echo2", Delegator.basic(() => new EchoHandler), settings)
        val p = TestProbe()
        val clashingServer: ServerRef = Server(cfg)
        p.watch(clashingServer.server)
        p.expectTerminated(clashingServer.server)
      }
    }

    "shutdown when a delegator fails to instantiate" in {
      val badDelegator : Delegator.Factory = (s, w) => throw new Exception("failed during delegator creation")

      withIOSystem{ implicit io =>
        val cfg = ServerConfig("echo", badDelegator, ServerSettings(TEST_PORT, delegatorCreationDuration = PollingDuration(200 milliseconds, Some(2L))))
        val serverProbe = TestProbe()
        val failedServer = Server(cfg)
        serverProbe.watch(failedServer.server)
        serverProbe.expectTerminated(failedServer.server)
      }
    }

    "shutdown when a delegator surpasses the allotted duration" in {
      val slowDelegator : Delegator.Factory = (s, w) => {
        Thread.sleep(400)
        new Delegator(s,w){
          def acceptNewConnection = Some(new EchoHandler())
        }}
      withIOSystem{ implicit io =>
        val cfg = ServerConfig("echo", slowDelegator, ServerSettings(TEST_PORT, delegatorCreationDuration = PollingDuration(200 milliseconds, Some(1L))))
        val serverProbe = TestProbe()
        val failedServer = Server(cfg)
        serverProbe.watch(failedServer.server)
        serverProbe.expectTerminated(failedServer.server)
      }

    }

    "shutting down a system kills client connections"  in {
      withIOSystem { implicit io =>
        val server = Server.basic("echo", TEST_PORT, () => new EchoHandler)
        val probe = TestProbe()
        probe watch server.server
        withServer(server) {
          val cio = IOSystem("client_io")
          val c = TestClient(cio, TEST_PORT, reconnect = false)
          Await.result(c.send(ByteString("HELLO")), 200.milliseconds) must equal(ByteString("HELLO"))
          io.shutdown()
          probe.expectTerminated(server.server)
          TestClient.waitForStatus(c, ConnectionStatus.NotConnected)
          cio.shutdown()
        }
      }
    }

    "get server info" in {
      val server = createServer(Delegator.basic(() => new EchoHandler))
      server.server ! Server.GetInfo
      expectMsg(50.milliseconds, Server.ServerInfo(0, ServerStatus.Bound))
      end(server)
    }
      

    "reject connection when maxed out" in {
      val settings = ServerSettings(
        port = TEST_PORT,
        maxConnections = 1
      )
      val server = createServer(Delegator.basic(() => new EchoHandler), Some(settings))
      val c1 = TestClient(server.system, TEST_PORT)
      expectConnections(server, 1)
      val c2 = TestClient(server.system, TEST_PORT, false)
      expectConnections(server, 1)
      end(server)
    }

    "open up spot when connection closes" in {
      val settings = ServerSettings(
        port = TEST_PORT,
        maxConnections = 1
      )
      val server = createServer(Delegator.basic(() => new EchoHandler), Some(settings))
      val c1 = TestClient(server.system, TEST_PORT)
      expectConnections(server, 1)
      val c2 = TestClient(server.system, TEST_PORT, waitForConnected = false, reconnect = false)
      //notice, we can't just check if the connection is connected because the
      //server will accept the connection before closing it
      intercept[service.ServiceClientException] {
        Await.result(c2.send(ByteString("hello")), 5000.milliseconds)
      }
      TestClient.waitForStatus(c2, ConnectionStatus.NotConnected)
      c1.disconnect()
      TestUtil.expectServerConnections(server, 0)
      val c3 = TestClient(server.system, TEST_PORT, waitForConnected = true, reconnect = false)
      TestUtil.expectServerConnections(server, 1)
      end(server)

    }

    "close connection when worker rejects" in {
      class AngryDelegator(server: ServerRef, worker: WorkerRef) extends Delegator(server, worker) {
        def acceptNewConnection = None // >:(
      }
      val server = createServer((s,w) => new AngryDelegator(s,w))
      val c1 = TestClient(server.system, TEST_PORT, reconnect = false, waitForConnected = false)
      intercept[service.ServiceClientException] {
        Await.result(c1.send(ByteString("testing")), 100.milliseconds)
      }
      TestClient.waitForStatus(c1, ConnectionStatus.NotConnected)
      end(server)

    }

    "times out idle client connection" in {
      withIOSystem { implicit io =>
        val probe = TestProbe()
        val config = ServerConfig(
          name = "test",
          settings = ServerSettings(
            port = TEST_PORT,
            maxIdleTime = 100.milliseconds
          ),
          delegatorFactory = Delegator.basic(() => new EchoHandler)
        )
        val server = Server(config)
        probe watch server.server
        waitForServer(server)
        val c = TestClient(server.system, TEST_PORT, reconnect = false)
        expectConnections(server, 1)
        Thread.sleep(1000)
        TestUtil.expectServerConnections(server, 0)
        //TODO c.isClosed must equal(true)
      }
    }

    "stash delegator broadcast messages until workers report ready" in {
      val (sys, mprobe) = FakeIOSystem.withManagerProbe()
      val config = EchoServerConfig
      val server = Server(config)(sys)
      val workerRouterProbe = TestProbe()
      server.delegatorBroadcast("TEST")
      mprobe.expectMsgType[WorkerManager.RegisterServer](50.milliseconds)
      mprobe.expectNoMsg(100.milliseconds)
      server.server ! WorkerManager.WorkersReady(workerRouterProbe.ref)
      workerRouterProbe.expectMsgType[akka.routing.Broadcast](50.milliseconds)
      server.server ! PoisonPill
      end(server)
    }

    "properly registers when worker initially times out" in {
      //notice, this test failed due to a timeout beforet the fix
      class SleepyDelegator(server: ServerRef, worker: WorkerRef) extends Delegator(server, worker) {
        Thread.sleep(600)
        def acceptNewConnection = None // >:(
      }
      end(createServer((s,w) => new SleepyDelegator(s,w), waitTime = 10.seconds))

    }

    "switch to high water timeout when connection count passes the high water mark" in {
      //for now this test only checks to see that the server switched its status
      withIOSystem { implicit io =>
        val config = ServerConfig(
          name = "highWaterTest",
          settings = ServerSettings(
            port = TEST_PORT,
            maxConnections = 10,
            lowWatermarkPercentage = 0.60,
            highWatermarkPercentage = 0.80,
            highWaterMaxIdleTime = 50.milliseconds,
            maxIdleTime = 1.hour
          ),
          delegatorFactory = Delegator.basic(() => new EchoHandler)
        )
        val server = Server(config)
        withServer(server) {
          val idleConnections = for{i <- 1 to 9} yield TestClient(server.system, TEST_PORT, reconnect = false)
          TestUtil.expectServerConnections(server, 9)
          Thread.sleep(1000) //have to wait a second since that's how often the check it done
          expectConnections(server, 0)
        }
      }
    }

    "delegator onShutdown is called when a worker shuts down" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val alive = Agent(0)
      class WhineyDelegator(server: ServerRef, worker: WorkerRef) extends Delegator(server, worker) {
        alive send {_ + 1}
        def acceptNewConnection = None // >:(

        override def onShutdown() {
          alive send {_ - 1}
        }
      }
      val server = createServer((s,w) => new WhineyDelegator(s,w), waitTime = 10.seconds)
      waitForServer(server)
      alive() must equal(server.system.config.numWorkers)
      end(server)
      alive() must equal(0)

    }
  }

  class TestDelegator(server: ServerRef, worker: WorkerRef) extends Delegator(server, worker) {
    def acceptNewConnection = Some(new EchoHandler)
    override def handleMessage = {
      case a: ActorRef => a.!(())
    }    
  }

  "delegator" must {
    "receive broadcast messages" in {
      withIOSystem { implicit io =>
        val server = createServer((s,w) => new TestDelegator(s,w))
        val dprobe = TestProbe()
        server.server ! Server.DelegatorBroadcast(dprobe.ref)
        dprobe.expectMsg(())
        dprobe.expectMsg(())
      }
    }
  }

}
