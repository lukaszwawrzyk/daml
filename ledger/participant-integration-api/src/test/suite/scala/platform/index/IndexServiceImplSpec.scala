// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.index

import com.daml.error.definitions.LedgerApiErrors
import com.daml.error.{ContextualizedErrorLogger, NoLogging}
import com.daml.ledger.api.domain.{Filters, InclusiveFilters, InterfaceFilter, TransactionFilter}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.Identifier
import com.daml.platform.TemplatePartiesFilter
import com.daml.platform.index.IndexServiceImpl.{
  checkUnknownTemplatesOrInterfaces,
  memoizedTransactionFilterProjection,
  templateFilter,
  wildcardFilter,
}
import com.daml.platform.index.IndexServiceImplSpec.Scope
import com.daml.platform.store.dao.EventProjectionProperties
import com.daml.platform.store.dao.EventProjectionProperties.InterfaceViewFilter
import com.daml.platform.store.packagemeta.PackageMetadataView
import com.daml.platform.store.packagemeta.PackageMetadataView.PackageMetadata
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IndexServiceImplSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  behavior of "IndexServiceImpl.memoizedTransactionFilterProjection"

  it should "give an empty result if no packages" in new Scope {
    when(view.current()).thenReturn(PackageMetadata())
    val memoFunc = memoizedTransactionFilterProjection(view, TransactionFilter(Map.empty), true)
    memoFunc() shouldBe None
  }

  it should "change the result in case of new package arrived" in new Scope {
    when(view.current()).thenReturn(PackageMetadata())
    // subscribing to iface1
    val memoFunc = memoizedTransactionFilterProjection(
      view,
      TransactionFilter(
        Map(party -> Filters(InclusiveFilters(Set(), Set(InterfaceFilter(iface1, true, true)))))
      ),
      true,
    )
    memoFunc() shouldBe None // no template implementing iface1
    when(view.current())
      .thenReturn(
        PackageMetadata(interfacesImplementedBy = Map(iface1 -> Set(template1)))
      ) // template1 implements iface1

    memoFunc() shouldBe Some(
      (
        TemplatePartiesFilter(Map(template1 -> Set(party)), Set()),
        EventProjectionProperties(
          true,
          Map.empty[String, Set[Identifier]],
          Map(party.toString -> Map(template1 -> InterfaceViewFilter(Set(iface1), true))),
        ),
      )
    ) // filter gets complicated, filters template1 for iface1, projects iface1

    when(view.current())
      .thenReturn(
        PackageMetadata(interfacesImplementedBy = Map(iface1 -> Set(template1, template2)))
      ) // template2 also implements iface1 as template1

    memoFunc() shouldBe Some(
      (
        TemplatePartiesFilter(
          Map(
            template1 -> Set(party),
            template2 -> Set(party),
          ),
          Set(),
        ),
        EventProjectionProperties(
          true,
          Map.empty[String, Set[Identifier]],
          Map(
            party.toString -> Map(
              template1 -> InterfaceViewFilter(Set(iface1), true),
              template2 -> InterfaceViewFilter(Set(iface1), true),
            )
          ),
        ),
      )
    ) // filter gets even more complicated, filters template1 and template2 for iface1, projects iface1 for both templates
  }

  behavior of "IndexServiceImpl.wildcardFilter"

  it should "give empty result for the empty input" in new Scope {
    wildcardFilter(
      TransactionFilter(Map.empty)
    ) shouldBe Set.empty
  }

  it should "provide a party filter for wildcard filter" in new Scope {
    wildcardFilter(
      TransactionFilter(Map(party -> Filters(None)))
    ) shouldBe Set(party)
  }

  it should "support multiple wildcard filters" in new Scope {
    wildcardFilter(
      TransactionFilter(
        Map(
          party -> Filters(None),
          party2 -> Filters(None),
        )
      )
    ) shouldBe Set(
      party,
      party2,
    )

    wildcardFilter(
      TransactionFilter(
        Map(
          party -> Filters(None),
          party2 -> Filters(
            Some(InclusiveFilters(templateIds = Set(template1), interfaceFilters = Set()))
          ),
        )
      )
    ) shouldBe Set(party)
  }

  it should "be treated as wildcard filter if templateIds and interfaceIds are empty" in new Scope {
    wildcardFilter(
      TransactionFilter(Map(party -> Filters(InclusiveFilters(Set(), Set()))))
    ) shouldBe Set(party)
  }

  behavior of "IndexServiceImpl.templateFilter"

  it should "give empty result for the empty input" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(Map.empty),
    ) shouldBe Map.empty
  }

  it should "provide an empty template filter for wildcard filters" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(Map(party -> Filters(None))),
    ) shouldBe Map.empty
  }

  it should "ignore wildcard filters and only include template filters" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(
        Map(
          party -> Filters(None),
          party2 -> Filters(None),
        )
      ),
    ) shouldBe Map.empty

    templateFilter(
      PackageMetadata(),
      TransactionFilter(
        Map(
          party -> Filters(None),
          party2 -> Filters(
            Some(InclusiveFilters(templateIds = Set(template1), interfaceFilters = Set()))
          ),
        )
      ),
    ) shouldBe Map(
      template1 -> Set(party2)
    )
  }

  it should "ignore wildcard filter of the shape where templateIds and interfaceIds are empty" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(Map(party -> Filters(InclusiveFilters(Set(), Set())))),
    ) shouldBe Map.empty
  }

  it should "provide a template filter for a simple template filter" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(Map(party -> Filters(InclusiveFilters(Set(template1), Set())))),
    ) shouldBe Map(template1 -> Set(party))
  }

  it should "provide an empty template filter if no template implementing this interface" in new Scope {
    templateFilter(
      PackageMetadata(),
      TransactionFilter(
        Map(party -> Filters(InclusiveFilters(Set(), Set(InterfaceFilter(iface1, true, true)))))
      ),
    ) shouldBe Map.empty
  }

  it should "provide a template filter for related interface filter" in new Scope {
    templateFilter(
      PackageMetadata(interfacesImplementedBy = Map(iface1 -> Set(template1))),
      TransactionFilter(
        Map(party -> Filters(InclusiveFilters(Set(), Set(InterfaceFilter(iface1, true, true)))))
      ),
    ) shouldBe Map(template1 -> Set(party))
  }

  it should "merge template filter and interface filter together" in new Scope {
    templateFilter(
      PackageMetadata(interfacesImplementedBy = Map(iface1 -> Set(template2))),
      TransactionFilter(
        Map(
          party -> Filters(
            InclusiveFilters(Set(template1), Set(InterfaceFilter(iface1, true, true)))
          )
        )
      ),
    ) shouldBe Map(template1 -> Set(party), template2 -> Set(party))
  }

  it should "merge multiple interface filters" in new Scope {
    templateFilter(
      PackageMetadata(interfacesImplementedBy =
        Map(iface1 -> Set(template1), iface2 -> Set(template2))
      ),
      TransactionFilter(
        Map(
          party -> Filters(
            InclusiveFilters(
              templateIds = Set(template3),
              interfaceFilters = Set(
                InterfaceFilter(iface1, true, true),
                InterfaceFilter(iface2, true, true),
              ),
            )
          )
        )
      ),
    ) shouldBe Map(
      template1 -> Set(party),
      template2 -> Set(party),
      template3 -> Set(party),
    )
  }

  behavior of "IndexServiceImpl.unknownTemplatesOrInterfaces"

  it should "provide an empty list in case of empty filter and package metadata" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(Map.empty),
      PackageMetadata(),
    ) shouldBe List()
  }

  it should "return an unknown template for not known template" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(Map(party -> Filters(InclusiveFilters(Set(template1), Set())))),
      PackageMetadata(),
    ) shouldBe List(Left(template1))
  }

  it should "return an unknown interface for not known interface" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(
        Map(party -> Filters(InclusiveFilters(Set(), Set(InterfaceFilter(iface1, true, true)))))
      ),
      PackageMetadata(),
    ) shouldBe List(Right(iface1))
  }

  it should "return zero unknown interfaces for known interface" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(
        Map(party -> Filters(InclusiveFilters(Set(), Set(InterfaceFilter(iface1, true, true)))))
      ),
      PackageMetadata(interfaces = Set(iface1)),
    ) shouldBe List()

  }

  it should "return zero unknown templates for known templates" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(Map(party -> Filters(InclusiveFilters(Set(template1), Set())))),
      PackageMetadata(templates = Set(template1)),
    ) shouldBe List()
  }

  it should "only return unknown templates and interfaces" in new Scope {
    checkUnknownTemplatesOrInterfaces(
      new TransactionFilter(
        Map(
          party -> Filters(
            InclusiveFilters(Set(template1), Set(InterfaceFilter(iface1, true, true)))
          ),
          party2 -> Filters(
            InclusiveFilters(Set(template2, template3), Set(InterfaceFilter(iface2, true, true)))
          ),
        )
      ),
      PackageMetadata(
        templates = Set(template1),
        interfaces = Set(iface1),
      ),
    ) shouldBe List(Right(iface2), Left(template2), Left(template3))
  }

  behavior of "IndexServiceImpl.invalidTemplateOrInterfaceMessage"

  private implicit val contextualizedErrorLogger: ContextualizedErrorLogger = NoLogging
  it should "provide no message if the list of invalid templates or interfaces is empty" in new Scope {
    LedgerApiErrors.RequestValidation.NotFound.TemplateOrInterfaceIdsNotFound
      .Reject(List.empty)
      .cause shouldBe ""
  }

  it should "combine a message containing invalid interfaces and templates together" in new Scope {
    LedgerApiErrors.RequestValidation.NotFound.TemplateOrInterfaceIdsNotFound
      .Reject(
        List(Right(iface2), Left(template2), Left(template3))
      )
      .cause shouldBe "Templates do not exist: [PackageName:ModuleName:template2, PackageName:ModuleName:template3]. Interfaces do not exist: [PackageName:ModuleName:iface2]."
  }

  it should "provide a message for invalid templates" in new Scope {
    LedgerApiErrors.RequestValidation.NotFound.TemplateOrInterfaceIdsNotFound
      .Reject(
        List(Left(template2), Left(template3))
      )
      .cause shouldBe "Templates do not exist: [PackageName:ModuleName:template2, PackageName:ModuleName:template3]."
  }

  it should "provide a message for invalid interfaces" in new Scope {
    LedgerApiErrors.RequestValidation.NotFound.TemplateOrInterfaceIdsNotFound
      .Reject(
        List(Right(iface1), Right(iface2))
      )
      .cause shouldBe "Interfaces do not exist: [PackageName:ModuleName:iface1, PackageName:ModuleName:iface2]."
  }
}

object IndexServiceImplSpec {
  trait Scope extends MockitoSugar {
    val party = Ref.Party.assertFromString("party")
    val party2 = Ref.Party.assertFromString("party2")
    val template1 = Ref.Identifier.assertFromString("PackageName:ModuleName:template1")
    val template2 = Ref.Identifier.assertFromString("PackageName:ModuleName:template2")
    val template3 = Ref.Identifier.assertFromString("PackageName:ModuleName:template3")
    val iface1 = Ref.Identifier.assertFromString("PackageName:ModuleName:iface1")
    val iface2 = Ref.Identifier.assertFromString("PackageName:ModuleName:iface2")
    val view = mock[PackageMetadataView]
  }
}
