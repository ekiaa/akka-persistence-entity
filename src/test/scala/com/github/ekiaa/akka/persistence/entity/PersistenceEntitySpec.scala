package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


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

  "A PersistenceEntity actor" when {

    "receive incoming Request message" should {

      "invoke handleIncomingRequest method of Entity" in withEntity { context =>
        import context._

        when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

        actor ! requestMessage_1

        val argument = ArgumentCaptor.forClass[Request, Request](classOf[Request])

        verify(entity, timeout(10000)).handleIncomingRequest(argument.capture())

        argument.getValue should ===(request_1)

      }

    }

    "invoke handleIncomingRequest of Entity" when {

      "it returns ResponseToActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystem" in withEntity { context =>
          import context._

          when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response_1, entity))

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

          when(entity.handleIncomingRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

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

          when(entity.handleIncomingRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

          actor ! requestMessage_1

          when(entity.handleIncomingResponse(any[Response])).thenReturn(ResponseToActor(response_2, entity))

          actor ! responseMessage_2

          val argument = ArgumentCaptor.forClass[Response, Response](classOf[Response])

          verify(entity, timeout(10000)).handleIncomingResponse(argument.capture())

          argument.getValue should ===(response_2)

        }

      }

      "invoke handleIncomingResponse of Entity" when {

        "it returns ResponseToActor reaction" should {

          "invoke sendMessage method of PersistenceEntitySystem" in withEntity { context =>
            import context._

            when(entity.handleIncomingRequest(any[Request])).thenReturn(RequestActor(entityId_2, request_2, entity))

            actor ! requestMessage_1

            when(entity.handleIncomingResponse(any[Response])).thenReturn(ResponseToActor(response_1, entity))

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

  case class TestContext(entitySystem: PersistenceEntitySystem,
                         entityId: EntityId,
                         entity: Entity,
                         actor: ActorRef,
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
    val actor = createActor(entityId, entitySystem)

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
        actor,
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

  private def createRequest: Request = {
    mock[Request](withSettings().serializable())
  }

  private def createResponse: Response = {
    mock[Response](withSettings().serializable())
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

  private def buildEntity(persistenceEntitySystem: PersistenceEntitySystem, entity: Entity): Unit = {
    when(persistenceEntitySystem.build(any[EntityId], any[Option[Entity]])).thenReturn(entity)
  }

  private def createActor(entityId: EntityId, persistenceEntitySystem: PersistenceEntitySystem): ActorRef = {
    system.actorOf(PersistenceEntity.props(entityId, persistenceEntitySystem))
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
      logger.info(s"Try to remove journal and snapshots before tests")
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
