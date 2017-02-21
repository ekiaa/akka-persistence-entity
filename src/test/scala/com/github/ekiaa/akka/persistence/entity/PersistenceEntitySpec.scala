package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, ActorSystem, ExtendedActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.ekiaa.akka.persistence.entity._
import com.typesafe.config.ConfigFactory
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._


class PersistenceEntitySpec
  extends TestKit(
    ActorSystem("PersistenceEntitySpec",
      ConfigFactory.parseString(
        """akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          |akka.persistence.journal.leveldb.dir = "target/journal"
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

        val extension = mock[PersistenceEntitySystemExtension]
        PersistenceEntitySystem.registerExtensionFactory({_ => extension})

        val builder = mock[EntityBuilder]
        PersistenceEntitySystem.registerEntityBuilder(builder)

        system.registerExtension(PersistenceEntitySystem)

        val entity = mock[Entity]
        when(builder.build(any[EntityId](), any[Option[Entity]]())).thenReturn(entity)

        val requesterEntityId = new TestEntityId(UUID.randomUUID().toString)
        val reactorEntityId = new TestEntityId(UUID.randomUUID().toString)

        when(entity.handleIncomingRequest(any[Request]())).thenReturn(Ignore(entity))

        val persistenceEntityActor = system.actorOf(Props(classOf[PersistenceEntity], reactorEntityId))

        persistenceEntityActor ! RequestMessage(
          requesterId = requesterEntityId,
          reactorId = reactorEntityId,
          request = TestRequest
        )

        verify(entity, timeout(1000)).handleIncomingRequest(any[Request]())

      }

    }

    "invoked handleIncomingRequest of Entity" when {

      "returned ResponseToActor reaction" should {

        "invoke sendMessage method of PersistenceEntitySystemExtension" in {

          val extension = mock[PersistenceEntitySystemExtension]
          PersistenceEntitySystem.registerExtensionFactory({_ => extension})

          val builder = mock[EntityBuilder]
          PersistenceEntitySystem.registerEntityBuilder(builder)

          system.registerExtension(PersistenceEntitySystem)

          val entity = mock[Entity]
          when(builder.build(any[EntityId](), any[Option[Entity]]())).thenReturn(entity)

          val requesterEntityId = new TestEntityId(UUID.randomUUID().toString)
          val reactorEntityId = new TestEntityId(UUID.randomUUID().toString)

          when(entity.handleIncomingRequest(any[Request]())).thenReturn(ResponseToActor(TestResponse, entity))

          val persistenceEntityActor = system.actorOf(Props(classOf[PersistenceEntity], reactorEntityId))

          persistenceEntityActor ! RequestMessage(
            requesterId = requesterEntityId,
            reactorId = reactorEntityId,
            request = TestRequest
          )

          verify(extension, timeout(1000)).sendMessage(any[Message]())(any[ActorContext]())

        }

      }

    }

  }

}

class TestEntityId(id: String) extends EntityId {

  override def className: Class[_] = classOf[Entity]

  override def persistenceId: String = s"TestEntity-$id"

}

case object TestRequest extends Request

case object TestResponse extends Response
