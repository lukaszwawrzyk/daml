// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.auth

import com.daml.ledger.api.domain.IdentityProviderConfig
import com.daml.logging.LoggingContext

import scala.concurrent.Future

trait IdentityProviderConfigLoader {

  def getIdentityProviderConfig(issuer: String)(implicit
      loggingContext: LoggingContext
  ): Future[IdentityProviderConfig]

}
