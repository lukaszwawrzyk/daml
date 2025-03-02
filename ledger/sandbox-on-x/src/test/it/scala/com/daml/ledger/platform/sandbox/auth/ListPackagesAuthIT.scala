// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.sandbox.auth

import com.daml.ledger.api.v1.package_service.{ListPackagesRequest, PackageServiceGrpc}

import scala.concurrent.Future

final class ListPackagesAuthIT extends PublicServiceCallAuthTests {

  override def serviceCallName: String = "PackageService#ListPackages"

  override def serviceCall(context: ServiceCallContext): Future[Any] =
    stub(PackageServiceGrpc.stub(channel), context.token)
      .listPackages(ListPackagesRequest())

}
