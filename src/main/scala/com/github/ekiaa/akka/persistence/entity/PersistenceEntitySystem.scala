package com.github.ekiaa.akka.persistence.entity

import java.util.UUID

import akka.actor.ActorContext

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

trait Reaction {
  val state: Entity
}

case class RequestActor(reactorId: EntityId, request: Request, state: Entity) extends Reaction

case class ResponseToActor(response: Response, state: Entity) extends Reaction

case class Ignore(state: Entity) extends Reaction

trait PersistedEvent extends Serializable

case class IncomingRequest(requestMessage: RequestMessage) extends PersistedEvent

case class OutgoingResponse(responseMessage: ResponseMessage) extends PersistedEvent

case class OutgoingRequest(requestMessage: RequestMessage) extends PersistedEvent

case class IncomingResponse(responseMessage: ResponseMessage) extends PersistedEvent
