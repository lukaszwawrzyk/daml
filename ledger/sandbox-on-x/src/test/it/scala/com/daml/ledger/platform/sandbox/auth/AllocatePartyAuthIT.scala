// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.sandbox.auth

import com.daml.ledger.api.v1.admin.party_management_service._

import scala.concurrent.Future

final class AllocatePartyAuthIT extends AdminOrIDPAdminServiceCallAuthTests {

  override def serviceCallName: String = "PartyManagementService#AllocateParty"

  override def serviceCall(context: ServiceCallContext): Future[Any] =
    stub(PartyManagementServiceGrpc.stub(channel), context.token)
      .allocateParty(AllocatePartyRequest(identityProviderId = context.identityProviderId))

}
