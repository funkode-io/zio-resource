/*
 * Copyright 2023 Carlos Verdes
 *
 * SPDX-License-Identifier: MIT
 */

package io.funkode.resource
package output

import scala.compiletime.*
import scala.quoted.*

import io.lemonlabs.uri.Urn
import io.netty.util.internal.StringUtil
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.*
import zio.schema.meta.MetaSchema
import zio.stream.*

import io.funkode.resource.model.*
import io.funkode.resource.model.Resource.Addressable

type ResourceApiCall[R] = IO[ResourceError, R]

trait ResourceStore:

  def resourceModel: ResourceModel

  def fetch(urn: Urn): ResourceStream[Resource]
  def save(resource: Resource): ResourceApiCall[Resource]
  def delete(urn: Urn): ResourceApiCall[Unit]
  def link(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit]
  def fetchRel(urn: Urn, relType: String): ResourceStream[Resource]

  def fetchOne(urn: Urn): ResourceApiCall[Resource] =
    for
      fetchOption <- fetch(urn).runHead
      result <- ZIO.fromOption(fetchOption).orElseFail(ResourceError.NotFoundError(urn, None))
    yield result

  inline def fetchAs[R: Resource.Addressable](urn: Urn): ResourceStream[Resource.Of[R]] =
    fetch(urn).map(_.of[R])

  inline def fetchOneAs[R: Resource.Addressable](urn: Urn): ResourceApiCall[Resource.Of[R]] =
    fetchOne(urn).map(_.of[R])

  inline def save[R: Resource.Addressable](
      inline addressable: R
  ): ResourceApiCall[Resource.Of[R]] =
    save(addressable.asJsonResource).map(_.of[R])

  inline def save[R: Resource.Addressable](
      inline typedResource: Resource.Of[R]
  ): ResourceApiCall[Resource] =
    save(typedResource.asJsonResource)

object ResourceStore:

  type WithResourceStore[R] = ZIO[ResourceStore, ResourceError, R]
  type WithResourceStreamStore[R] = ZStream[ResourceStore, ResourceError, R]

  inline def withStore[R](f: ResourceStore => WithResourceStore[R]) = ZIO.service[ResourceStore].flatMap(f)
  inline def withStreamStore[R](f: ResourceStore => WithResourceStreamStore[R]) =
    ZStream.service[ResourceStore].flatMap(f)

  def fetch(urn: Urn): WithResourceStreamStore[Resource] = withStreamStore(_.fetch(urn))

  def fetchOne(urn: Urn): WithResourceStore[Resource] = withStore(_.fetchOne(urn))

  inline def fetchAs[R: Addressable](urn: Urn): WithResourceStreamStore[Resource.Of[R]] = withStreamStore(
    _.fetchAs[R](urn)
  )
  inline def fetchOneAs[R: Addressable](urn: Urn): WithResourceStore[Resource.Of[R]] = withStore(
    _.fetchOneAs[R](urn)
  )

  def save(resource: Resource): WithResourceStore[Resource] =
    withStore(_.save(resource))

  inline def save[R: Resource.Addressable](
      inline addressable: R
  ): WithResourceStore[Resource.Of[R]] =
    withStore(_.save(addressable))

  inline def save[R: Resource.Addressable](
      inline typedResource: Resource.Of[R]
  ): WithResourceStore[Resource] =
    withStore(_.save(typedResource))

  def delete(urn: Urn): WithResourceStore[Unit] = withStore(_.delete(urn))

  def link(leftUrn: Urn, relType: String, rightUrn: Urn): WithResourceStore[Unit] =
    withStore(_.link(leftUrn, relType, rightUrn))

  def fetchRel(urn: Urn, relType: String): WithResourceStreamStore[Resource] =
    withStreamStore(_.fetchRel(urn, relType))

  trait InMemoryStore extends ResourceStore:

    private val storeMap: collection.mutable.Map[Urn, Resource] = collection.mutable.Map.empty
    private val linksMap: collection.mutable.Map[Urn, collection.mutable.Map[String, Resource]] =
      collection.mutable.Map.empty

    def resourceModel: ResourceModel = ResourceModel("in-mem", Map.empty)

    def fetch(urn: Urn): ResourceStream[Resource] =
      ZStream.fromZIO(ZIO.fromOption(storeMap.get(urn)).orElseFail(ResourceError.NotFoundError(urn)))

    def save(resource: Resource): ResourceApiCall[Resource] =
      ZIO.fromOption(storeMap.put(resource.urn, resource)).orElse(ZIO.succeed(resource))

    def delete(urn: Urn): ResourceApiCall[Unit] =
      ZIO.succeed(linksMap.remove(urn)) *>
        ZIO.fromOption(storeMap.remove(urn)).orElseFail(ResourceError.NotFoundError(urn)) *>
        ZIO.succeed(())

    def link(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit] =
      for
        _ <- ZIO.fromOption(storeMap.get(leftUrn)).orElseFail(ResourceError.NotFoundError(leftUrn))
        rightResource <-
          ZIO
            .fromOption(storeMap.get(rightUrn))
            .orElseFail(ResourceError.NotFoundError(rightUrn))
        _ <-
          ZIO.succeed(
            linksMap
              .getOrElseUpdate(leftUrn, collection.mutable.Map.empty)
              .put(relType, rightResource)
              .getOrElse(rightResource)
          )
      yield ()

    def fetchRel(urn: Urn, relType: String): ResourceStream[Resource] =
      ZStream.fromZIO(
        ZIO
          .fromOption(linksMap.get(urn).map(_.get(relType)).flatten)
          .orElseFail(
            ResourceError
              .NotFoundError(urn, Some(new Throwable(s"Rel type $relType not found for urn: $urn")))
          )
      )

  def inMemory: ZLayer[Any, ResourceError, ResourceStore] = ZLayer(ZIO.succeed(new InMemoryStore {}))
