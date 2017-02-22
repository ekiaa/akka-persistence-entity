package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.{ActorContext, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


class PersistenceEntitySpec
  extends TestKit(
    ActorSystem("PersistenceEntitySpec",
      ConfigFactory.parseString(
        """akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          |akka.persistence.journal.leveldb.dir = "target/journal"
          |akka.persistence.journal.leveldb.native = off
          |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
          |akka.persistence.snapshot-store.local.dir = "target/snapshots"
        """.stripMargin
      )
    )
  ) with ImplicitSender
    with MockitoSugar
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A PersistenceEntity" when {

    "receive Request" should {

      "invoke handleIncomingRequest method of Entity" in {

        val requesterEntityId = mock[EntityId](withSettings().serializable())

        val reactorEntityId = mock[EntityId](withSettings().serializable())
        when(reactorEntityId.persistenceId).thenReturn(UUID.randomUUID().toString)

        val entity = mock[Entity]
        when(entity.entityId).thenReturn(reactorEntityId)

        val persistenceEntitySystem = mock[PersistenceEntitySystem]
        when(persistenceEntitySystem.build(any[EntityId], any[Option[Entity]])).thenReturn(entity)
        doNothing().when(persistenceEntitySystem).sendMessage(any[Message])(any[ActorContext])

        val response = mock[Response](withSettings().serializable())

        when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response, entity))

        val persistenceEntityActor = system.actorOf(PersistenceEntity.props(reactorEntityId, persistenceEntitySystem))

        val request = mock[Request](withSettings().serializable())

        persistenceEntityActor ! RequestMessage(
          requesterId = requesterEntityId,
          reactorId = reactorEntityId,
          request = request
        )

        verify(entity, timeout(10000)).handleIncomingRequest(any[Request]())

      }

    }

    "invoked handleIncomingRequest of Entity" when {

      "returned ResponseToActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystemExtension" in {

          val requesterEntityId = mock[EntityId](withSettings().serializable())
          when(requesterEntityId.persistenceId).thenReturn(UUID.randomUUID().toString)

          val reactorEntityId = mock[EntityId](withSettings().serializable())
          when(reactorEntityId.persistenceId).thenReturn(UUID.randomUUID().toString)

          val entity = mock[Entity]
          when(entity.entityId).thenReturn(reactorEntityId)

          val persistenceEntitySystem = mock[PersistenceEntitySystem]
          when(persistenceEntitySystem.build(any[EntityId], any[Option[Entity]])).thenReturn(entity)

          val response = mock[Response](withSettings().serializable())

          when(entity.handleIncomingRequest(any[Request])).thenReturn(ResponseToActor(response, entity))

          val persistenceEntityActor = system.actorOf(PersistenceEntity.props(reactorEntityId, persistenceEntitySystem))

          val request = mock[Request](withSettings().serializable())

          persistenceEntityActor ! RequestMessage(
            requesterId = requesterEntityId,
            reactorId = reactorEntityId,
            request = request
          )

          verify(persistenceEntitySystem, timeout(10000)).sendMessage(any[Message]())(any[ActorContext]())

        }

      }

    }

  }

}
