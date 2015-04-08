package io.reactivesqrs.testdomain.api

import io.reactivecqrs.api.command.{FirstCommand, FollowingCommand}
import io.reactivecqrs.api.guid.{AggregateId, AggregateVersion}


case class RegisterUser(name: String)
  extends FirstCommand[User, RegisterUserResult]
case class RegisterUserResult(registeredUserId: AggregateId)


case class ChangeUserAddress(aggregateId: AggregateId, expectedVersion: AggregateVersion, city: String, street: String, number: String)
  extends FollowingCommand[User, EmptyResult]


case class UndoUserChange(aggregateId: AggregateId, expectedVersion: AggregateVersion, stepsToUndo: Int)
  extends FollowingCommand[User, EmptyResult]


case class DeleteUser(aggregateId: AggregateId, expectedVersion: AggregateVersion)
  extends FollowingCommand[User, EmptyResult]


case class EmptyResult()

