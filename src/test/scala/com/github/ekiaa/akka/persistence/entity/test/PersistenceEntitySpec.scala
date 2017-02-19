package com.github.ekiaa.akka.persistence.entity.test

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, ActorSystem, ExtendedActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.ekiaa.akka.persistence.entity._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

object PersistenceEntitySpec {

  def prepareActorSystem: ActorSystem = {

    PersistenceEntitySystem.registerExtensionFactory(
      { actorSystem => new TestPersistenceEntitySystemExtension(actorSystem) }
    )
    PersistenceEntitySystem.registerEntityBuilder(
      new EntityBuilder {
        override def build(entityId: EntityId, entitySnapshot: Option[Entity]): Entity =
          entityId match {
            case eid: TestEntityId =>
              new TestEntity(eid)
          }
      }
    )

    ActorSystem("PersistenceEntitySpec",
      ConfigFactory.parseString(
        """akka.extensions = ["com.github.ekiaa.akka.persistence.entity.PersistenceEntitySystem"]
          |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
          |akka.persistence.journal.leveldb.dir = "target/journal"
          |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
          |akka.persistence.snapshot-store.local.dir = "target/snapshots"
        """.stripMargin
      )
    )
  }

}

class PersistenceEntitySpec
  extends TestKit(PersistenceEntitySpec.prepareActorSystem)
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A PersistenceActor" when {

    "receive Request" should {

      "invoke handleIncomingRequest method of Entity" in {

        val requesterEntityId = new TestEntityId(UUID.randomUUID().toString, testActor)

        val reactorEntityId = new TestEntityId(UUID.randomUUID().toString, testActor)

        val persistenceEntityActor = system.actorOf(Props(classOf[PersistenceEntity], reactorEntityId))

        persistenceEntityActor ! RequestMessage(
          requesterId = requesterEntityId,
          reactorId = reactorEntityId,
          request = TestRequest
        )

        expectMsg("RequestReceived")

      }

    }

  }

}

class TestEntityId(id: String, val testActor: ActorRef) extends EntityId {

  override def className: Class[_] = classOf[TestEntity]

  override def persistenceId: String = s"TestEntity-$id"

}

case object TestRequest extends Request

case object TestResponse extends Response

class TestEntity(val entityId: TestEntityId) extends Entity {

  override def handleIncomingRequest(request: Request): Reaction = {
    request match {
      case TestRequest =>
        entityId.testActor ! "RequestReceived"
        Ignore(this)
    }
  }

  override def handleIncomingResponse(response: Response): Reaction = {
    Ignore(this)
  }

}

class TestPersistenceEntitySystemExtension(actorSystem: ExtendedActorSystem) extends PersistenceEntitySystemExtension {

  override def sendMessage(message: Message)(implicit context: ActorContext): Unit = ???

}