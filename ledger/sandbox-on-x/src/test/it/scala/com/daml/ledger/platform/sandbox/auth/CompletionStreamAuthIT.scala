// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.sandbox.auth

import com.daml.ledger.api.v1.admin.{user_management_service => proto}
import com.daml.ledger.api.v1.command_completion_service.{
  CommandCompletionServiceGrpc,
  CompletionStreamRequest,
  CompletionStreamResponse,
}
import com.daml.platform.testing.StreamConsumer
import com.daml.test.evidence.scalatest.ScalaTestSupport.Implicits._
import io.grpc.stub.StreamObserver

import scala.concurrent.Future

final class CompletionStreamAuthIT
    extends ExpiringStreamServiceCallAuthTests[CompletionStreamResponse] {

  override def serviceCallName: String = "CommandCompletionService#CompletionStream"

  override val testCanReadAsMainActor: Boolean = false

  override protected def stream(
      context: ServiceCallContext
  ): StreamObserver[CompletionStreamResponse] => Unit = streamFor(serviceCallName, context)

  private def mkRequest(applicationId: String) =
    new CompletionStreamRequest(
      unwrappedLedgerId,
      applicationId,
      List(mainActor),
      Some(ledgerBegin),
    )

  private def streamFor(
      applicationId: String,
      context: ServiceCallContext,
  ): StreamObserver[CompletionStreamResponse] => Unit =
    observer =>
      stub(CommandCompletionServiceGrpc.stub(channel), context.token)
        .completionStream(mkRequest(context.applicationId(applicationId)), observer)

  override def serviceCallWithMainActorUser(
      userPrefix: String,
      rights: Vector[proto.Right.Kind],
  ): Future[Any] =
    for {
      (_, context) <- createUserByAdmin(userPrefix + mainActor, rights = rights.map(proto.Right(_)))
      _ <- submitAndWait(context.token, "", party = mainActor)
      _ <- new StreamConsumer[CompletionStreamResponse](
        streamFor("", context.copy(includeApplicationId = false))
      ).first()
    } yield ()

  // The completion stream is the one read-only endpoint where the application
  // identifier is part of the request. Hence, we didn't put this test in a shared
  // trait to no have to have to override the result for the transaction service
  // authorization tests.

  it should "allow calls with the correct application ID" taggedAs securityAsset
    .setHappyCase(
      "Ledger API client can make a call with the correct application ID"
    ) in {
    expectSuccess(serviceCall(canReadAsMainActorActualApplicationId))
  }

  it should "deny calls with an unknown application ID" taggedAs securityAsset.setAttack(
    attackPermissionDenied(threat = "Present a JWT with an unknown application ID")
  ) in {
    expectPermissionDenied(serviceCall(canReadAsMainActorRandomApplicationId))
  }

  it should "allow calls with an application ID present in the message and a token with an empty application ID" taggedAs securityAsset
    .setHappyCase(
      "Ledger API client can make a call with an application ID present in the message and a token with an empty application ID"
    ) in {
    expectSuccess(
      serviceCall(canActAsMainActorActualApplicationId.copy(includeApplicationId = false))
    )
  }

  it should "deny calls with an application ID present in the message and a token without application ID" taggedAs securityAsset
    .setAttack(
      attackInvalidArgument(threat =
        "Exploit a call with an application ID present in the message and a token without application ID"
      )
    ) in {
    // Note: need canActAsMainActor as the test first submits a change that it then listens for.
    expectInvalidArgument(serviceCall(canActAsMainActor.copy(includeApplicationId = false)))
  }
}
