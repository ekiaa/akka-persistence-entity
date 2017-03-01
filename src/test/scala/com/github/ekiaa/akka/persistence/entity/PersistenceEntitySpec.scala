package com.github.ekiaa.akka.persistence.entity

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorContext, ActorRef, ActorSystem, Kill, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration.FiniteDuration


class PersistenceEntitySpec
  extends TestKit(
    ActorSystem("PersistenceEntitySpec",
      ConfigFactory.parseString(
        """akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          |akka.persistence.journal.leveldb.dir = "target/test/journal"
          |akka.persistence.journal.leveldb.native = off
          |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
          |akka.persistence.snapshot-store.local.dir = "target/test/snapshots"
        """.stripMargin
      )
    )
  ) with ImplicitSender
    with MockitoSugar
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with StrictLogging {

  override def beforeAll(): Unit = {
    removeLevelDBDirectories()
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    removeLevelDBDirectories()
  }

  case class TestContext(entitySystem: PersistenceEntitySystem,
                         entityId: EntityId,
                         entity: Entity,
                         entityId_1: EntityId,
                         request_1: Request,
                         requestMessage_1: RequestMessage,
                         response_1: Response,
                         entityId_2: EntityId,
                         request_2: Request,
                         response_2: Response,
                         responseMessage_2: ResponseMessage)

  def withEntity(testCode: TestContext => Any): Unit = {

    /*   entityId_1       entityId       entityId_2   */
    /*       |   request_1   |               |        */
    /*       |-------------->|   request_2   |        */
    /*       |               |-------------->|        */
    /*       |               |   response_2  |        */
    /*       |   response_1  |<--------------|        */
    /*       |<--------------|               |        */
    /*       |               |               |        */

    val entitySystem = createSystem

    val entityId = createEntityId
    val entity = createEntity(entityId)
    buildEntity(entitySystem, entity)

    val entityId_1 = createEntityId
    val request_1 = createRequest
    val requestMessage_1 = createRequestMessage(entityId_1, entityId, request_1)
    val response_1 = createResponse

    val entityId_2 = createEntityId
    val request_2 = createRequest
    val response_2 = createResponse
    val responseMessage_2 = createResponseMessage(entityId, entityId_2, response_2, UUID.randomUUID().toString)

    testCode(
      TestContext(
        entitySystem,
        entityId,
        entity,
        entityId_1,
        request_1,
        requestMessage_1,
        response_1,
        entityId_2,
        request_2,
        response_2,
        responseMessage_2
      )
    )
  }

  "A PersistenceEntity actor" when {

    "recovery state" when {

      "there are no snapshot and events" should {

        "invoke build method of PersistenceEntitySystem" in withEntity { context =>
          import context._

          createActor(entityId, entitySystem)

          val argument = ArgumentCaptor.forClass[EntityId, EntityId](classOf[EntityId])

          verify(entitySystem, timeout(10000).times(1)).build(argument.capture())

          argument.getValue should ===(entityId)

        }

      }

      "there is no only snapshot" when {

        "recovery incoming Request" should {

          "invoke build method of PersistenceEntitySystem" in withEntity { context =>
            import context._

            val actor = createActor(entityId, entitySystem)

            when(entity.handleRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

            actor ! requestMessage_1

            terminateActor(actor)

            createActor(entityId, entitySystem)

            val argument = ArgumentCaptor.forClass[EntityId, EntityId](classOf[EntityId])

            verify(entitySystem, timeout(10000).times(2)).build(argument.capture())

            argument.getValue should ===(entityId)

          }

          "invoke handleRequest method of Entity" in withEntity { context =>
            import context._

            val actor = createActor(entityId, entitySystem)

            when(entity.handleRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

            actor ! requestMessage_1

            terminateActor(actor)

            createActor(entityId, entitySystem)

            val argument = ArgumentCaptor.forClass[Request, Request](classOf[Request])

            verify(entity, timeout(10000).times(2)).handleRequest(argument.capture())

            val request = argument.getValue
            request.isInstanceOf[TestRequest] should ===(true)
            val testRequest = request.asInstanceOf[TestRequest]
            testRequest.id should ===(request_1.asInstanceOf[TestRequest].id)

          }

          "store new entity state in Reaction returned by handleRequest" in {}

          "resend it if this is a last recovered event" in {

          }

        }

        "recovery outgoing Request" should {

          "resend it if this is a last recovered event" in {}

        }

        "recovery incoming Response" should {

          "invoke handleResponse method of Entity" in {}

          "store new entity state in Reaction returned by handleResponse" in {}

          "apply Reaction returned by handleResponse if this is a last recovered event" in {}

        }

        "recovery outgoing Response" should {

          "return it if in future will be received previously processed Request" in withEntity { context =>
            import context._

            val actor = createActor(entityId, entitySystem)

            when(entity.handleRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

            actor ! requestMessage_1

            terminateActor(actor)

            val sameActor = createActor(entityId, entitySystem)

            sameActor ! requestMessage_1

            val argument = ArgumentCaptor.forClass[Message, ResponseMessage](classOf[ResponseMessage])

            verify(entitySystem, timeout(10000).times(2)).sendMessage(argument.capture())(any[ActorContext]())

            argument.getValue.isInstanceOf[ResponseMessage] should ===(true)
            val responseMessage = argument.getValue.asInstanceOf[ResponseMessage]
            responseMessage.response.isInstanceOf[TestResponse] should ===(true)
            val response = responseMessage.response.asInstanceOf[TestResponse]
            response.id should ===(response_1.asInstanceOf[TestResponse].id)

          }

        }

      }

      "recovered snapshot" should {

        "invoke recovery method of PersistenceEntitySystem" in {

        }

      }

    }

    "receive incoming Request message" should {

      "invoke handleIncomingRequest method of Entity" in withEntity { context =>
        import context._

        when(entity.handleRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

        val actor = createActor(entityId, entitySystem)

        actor ! requestMessage_1

        val argument = ArgumentCaptor.forClass[Request, Request](classOf[Request])

        verify(entity, timeout(10000).times(1)).handleRequest(argument.capture())

        argument.getValue should ===(request_1)

      }

    }

    "invoke handleIncomingRequest of Entity" when {

      "it returns ResponseToActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystem" in withEntity { context =>
          import context._

          when(entity.handleRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

          val actor = createActor(entityId, entitySystem)

          actor ! requestMessage_1

          val argument = ArgumentCaptor.forClass[Message, ResponseMessage](classOf[ResponseMessage])

          verify(entitySystem, timeout(10000).times(1)).sendMessage(argument.capture())(any[ActorContext]())

          val message = argument.getValue
          message.requesterId should ===(entityId_1)
          message.reactorId should ===(entityId)
          message.correlationId should ===(requestMessage_1.correlationId)
          message shouldBe a [ResponseMessage]
          message.asInstanceOf[ResponseMessage].response should ===(response_1)

        }

      }

      "it returns RequestActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystem with RequestMessage argument" in withEntity { context =>
          import context._

          when(entity.handleRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

          val actor = createActor(entityId, entitySystem)

          actor ! requestMessage_1

          val argument = ArgumentCaptor.forClass[Message, RequestMessage](classOf[RequestMessage])

          verify(entitySystem, timeout(10000).times(1)).sendMessage(argument.capture())(any[ActorContext]())

          val message = argument.getValue
          message.requesterId should ===(entityId)
          message.reactorId should ===(entityId_2)
          message shouldBe a [RequestMessage]
          message.asInstanceOf[RequestMessage].request should ===(request_2)

        }

      }

    }

    "request another Actor after handling incoming Request message" when {

      "receive Response message" should {

        "invoke handleIncomingResponse method of Entity" in withEntity { context =>
          import context._

          when(entity.handleRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

          val actor = createActor(entityId, entitySystem)

          actor ! requestMessage_1

          when(entity.handleResponse(any[Response])).thenReturn(ResponseToActor(response_2, entity))

          actor ! responseMessage_2

          val argument = ArgumentCaptor.forClass[Response, Response](classOf[Response])

          verify(entity, timeout(10000)).handleResponse(argument.capture())

          argument.getValue should ===(response_2)

        }

      }

      "invoke handleIncomingResponse of Entity" when {

        "it returns ResponseToActor reaction" should {

          "invoke sendMessage method of PersistenceEntitySystem" in withEntity { context =>
            import context._

            when(entity.handleRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

            val actor = createActor(entityId, entitySystem)

            actor ! requestMessage_1

            when(entity.handleResponse(any[Response])).thenReturn(ResponseToActor(response_1, entity))

            actor ! responseMessage_2

            val argument = ArgumentCaptor.forClass[Message, ResponseMessage](classOf[ResponseMessage])

            verify(entitySystem, timeout(10000).times(2)).sendMessage(argument.capture())(any[ActorContext]())

            val message = argument.getValue
            message.requesterId should ===(entityId_1)
            message.reactorId should ===(entityId)
            message.correlationId should ===(requestMessage_1.correlationId)
            message shouldBe a [ResponseMessage]
            message.asInstanceOf[ResponseMessage].response should ===(response_1)

          }

        }

      }

    }

  }

  private def createRequest: Request = {
    TestRequest()
  }

  private def createResponse: Response = {
    TestResponse()
  }

  private def createEntityId: EntityId = {
    val entityId = mock[EntityId](withSettings().serializable())
    when(entityId.persistenceId).thenReturn(UUID.randomUUID().toString)
    entityId
  }

  private def createEntity(entityId: EntityId): Entity = {
    val entity = mock[Entity]
    when(entity.entityId).thenReturn(entityId)
    entity
  }

  private def createSystem: PersistenceEntitySystem = {
    val persistenceEntitySystem = mock[PersistenceEntitySystem]
    doNothing().when(persistenceEntitySystem).sendMessage(any[Message])(any[ActorContext])
    persistenceEntitySystem
  }

  private def buildEntity(entitySystem: PersistenceEntitySystem, entity: Entity): Unit = {
    when(entitySystem.build(any[EntityId])).thenReturn(entity)
  }

  private def createActor(entityId: EntityId, entitySystem: PersistenceEntitySystem): ActorRef = {
    val actorRef = system.actorOf(PersistenceEntity.props(entityId, entitySystem))
    actorRef ! PersistenceEntity.VerifyStarted
    expectMsg(FiniteDuration(10, TimeUnit.SECONDS), PersistenceEntity.Started)
    actorRef
  }

  private def terminateActor(actorRef: ActorRef): Unit = {
    watch(actorRef)
    actorRef ! PersistenceEntity.Terminate
    expectTerminated(actorRef, FiniteDuration(10, TimeUnit.SECONDS))
  }

  private def createRequestMessage(requesterId: EntityId, reactorId: EntityId, request: Request): RequestMessage = {
    RequestMessage(
      requesterId = requesterId,
      reactorId = reactorId,
      request = request
    )
  }

  private def createResponseMessage(requesterId: EntityId, reactorId: EntityId, response: Response, correlationId: String): ResponseMessage = {
    ResponseMessage(
      correlationId = correlationId,
      requesterId = requesterId,
      reactorId = reactorId,
      response = response
    )
  }

  private def removeLevelDBDirectories(): Unit = {
    try {
      logger.info(s"Try to remove journal and snapshots")
      val result = sys.props("os.name").toLowerCase match {
        case x if x contains "windows" =>
          scala.sys.process.stringToProcess("cmd /C rmdir /Q /S target\\test").!
        case _ =>
          scala.sys.process.stringToProcess("rm -r target/test").!
      }
      logger.info(s"Result: $result")
    } catch {
      case e: Throwable =>
        logger.error(s"Exception occurred when command run: [${e.getMessage}]")
    }
  }

}

case class TestRequest(id: String = UUID.randomUUID().toString) extends Request with Serializable

case class TestResponse(id: String = UUID.randomUUID().toString) extends Response with Serializable
