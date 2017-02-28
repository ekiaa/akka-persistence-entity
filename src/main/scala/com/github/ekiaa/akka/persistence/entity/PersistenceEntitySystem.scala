package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.{ActorContext, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}

//object PersistenceEntitySystem extends ExtensionId[PersistenceEntitySystemExtension] with ExtensionIdProvider {
//
//  type ExtensionFactory = (ExtendedActorSystem) => PersistenceEntitySystemExtension
//
//  private var entityBuilder: Option[EntityBuilder] = None
//
//  def registerEntityBuilder(builder: EntityBuilder): Unit = {
//    entityBuilder = Some(builder)
//  }
//
//  def getEntityBuilder: EntityBuilder = {
//    require(entityBuilder.isDefined, "PersistenceEntitySystem: entityBuilder should be registered when getEntityBuilder invoked")
//    entityBuilder.get
//  }
//
//  private var extensionFactory: Option[ExtensionFactory] = None
//
//  def registerExtensionFactory(factory: ExtensionFactory): Unit = {
//    extensionFactory = Some(factory)
//  }
//
//  override def createExtension(system: ExtendedActorSystem): PersistenceEntitySystemExtension = {
//    require(extensionFactory.isDefined, "PersistenceEntitySystem: extensionFactory should not be null when createExtension invoked")
//    extensionFactory.get(system)
//  }
//
//  override def lookup(): ExtensionId[_ <: Extension] = PersistenceEntitySystem
//
//  override def get(system: ActorSystem): PersistenceEntitySystemExtension = super.get(system)
//
//}
//
//trait PersistenceEntitySystemExtension extends Extension {
//
//  def sendMessage(message: Message)(implicit context: ActorContext): Unit
//
//}
//
//trait EntityBuilder {
//
//  def build(entityId: EntityId, entitySnapshot: Option[Entity]): Entity
//
//}

trait PersistenceEntitySystem {

  def build(entityId: EntityId): Entity

  def recovery(snapshot: Entity): Entity

  def sendMessage(message: Message)(implicit context: ActorContext): Unit

}


trait Entity {

  def entityId: EntityId

  def init(): Entity

  def handleRequest(request: Request): Reaction

  def handleResponse(response: Response): Reaction

}

trait EntityId extends Serializable {

  def persistenceId: String

}

trait Message extends Serializable {

  val id: String

  val correlationId: String

  val requesterId: EntityId

  val reactorId: EntityId

}

trait Request extends Serializable

trait Response extends Serializable

case class RequestMessage(id: String = UUID.randomUUID().toString,
                          correlationId: String = UUID.randomUUID().toString,
                          requesterId: EntityId,
                          reactorId: EntityId,
                          request: Request
                         ) extends Message

case class ResponseMessage(id: String = UUID.randomUUID().toString,
                           correlationId: String,
                           requesterId: EntityId,
                           reactorId: EntityId,
                           response: Response
                          ) extends Message

trait Reaction { val state: Entity }
case class RequestActor(reactorId: EntityId, request: Request, state: Entity) extends Reaction
case class ResponseToActor(response: Response, state: Entity) extends Reaction
case class Ignore(state: Entity) extends Reaction

trait PersistedEvent extends Serializable
case class IncomingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class OutgoingResponse(responseMessage: ResponseMessage) extends PersistedEvent
case class OutgoingRequest(requestMessage: RequestMessage) extends PersistedEvent
case class IncomingResponse(responseMessage: ResponseMessage) extends PersistedEvent
