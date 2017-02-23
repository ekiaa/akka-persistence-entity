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

      "invoke handleIncomingRequest method of Entity" in {

        val request = createRequest
        val response = createResponse
        val requesterId = createEntityId
        val reactorId = createEntityId
        val entity = createEntity(reactorId)
        val system = createSystem
        buildEntity(system, entity)
        val actor = createActor(reactorId, system)
        val requestMessage = createRequestMessage(requesterId, reactorId, request)

        when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response, entity))

        actor ! requestMessage

        val argument = ArgumentCaptor.forClass[Request, Request](classOf[Request])

        verify(entity, timeout(10000)).handleIncomingRequest(argument.capture())

        argument.getValue should ===(request)

      }

    }

    "invoke handleIncomingRequest of Entity" when {

      "it returns ResponseToActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystem" in {

          val request = createRequest
          val response = createResponse
          val requesterId = createEntityId
          val reactorId = createEntityId
          val entity = createEntity(reactorId)
          val system = createSystem
          buildEntity(system, entity)
          val actor = createActor(reactorId, system)
          val requestMessage = createRequestMessage(requesterId, reactorId, request)

          when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response, entity))

          actor ! requestMessage

          val argument = ArgumentCaptor.forClass[Message, ResponseMessage](classOf[ResponseMessage])

          verify(system, timeout(10000)).sendMessage(argument.capture())(any[ActorContext]())

          val message = argument.getValue
          message.requesterId should ===(requesterId)
          message.reactorId should ===(reactorId)
          message.correlationId should ===(requestMessage.correlationId)

        }

      }

      "it returns RequestActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystem with RequestMessage argument" in {

          val request = createRequest
          val request2 = createRequest
          val requesterId = createEntityId
          val reactorId = createEntityId
          val requesterId2 = createEntityId
          val entity = createEntity(reactorId)
          val system = createSystem
          buildEntity(system, entity)
          val actor = createActor(reactorId, system)
          val requestMessage = createRequestMessage(requesterId, reactorId, request)

          when(entity.handleIncomingRequest(any[Request])).thenReturn(RequestActor(requesterId2, request2, entity))

          actor ! requestMessage

          val argument = ArgumentCaptor.forClass[Message, RequestMessage](classOf[RequestMessage])

          verify(system, timeout(10000)).sendMessage(argument.capture())(any[ActorContext]())

          val message = argument.getValue
          message.requesterId should ===(reactorId)
          message.reactorId should ===(requesterId2)

        }

      }

    }

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
