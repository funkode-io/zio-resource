/*
 * Copyright 2023 Carlos Verdes
 *
 * SPDX-License-Identifier: MIT
 */

package io.funkode.resource
package output

import io.lemonlabs.uri.Urn
import zio.*
import zio.stream.*
import io.funkode.resource.model.*
import io.funkode.resource.model.Resource.Addressable

type ResourceApiCall[R] = IO[ResourceError, R]

trait ResourceStore:

  def resourceModel: ResourceModel

  def fetch(urn: Urn | Query): ResourceStream[Resource]
  def save(resource: Resource): ResourceApiCall[Resource]
  def delete(urn: Urn): ResourceApiCall[Resource]
  def link(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit]
  def unlink(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit]
  def fetchRel(urn: Urn, relType: String): ResourceStream[Resource]
  def transaction[R](body: ResourceStore => ResourceApiCall[R]): ResourceApiCall[R]

  def fetchOne(urn: Urn | Query): ResourceApiCall[Resource] =
    fetch(urn).runHead.someOrFail(ResourceError.NotFoundError(urn, None))

  inline def fetchAs[R](urn: Urn | Query): ResourceStream[Resource.Of[R]] =
    fetch(urn).map(_.of[R])

  inline def fetchAndConsume[R](urn: Urn | Query): ResourceStream[Resource.InMemory[R]] =
    fetch(urn).mapZIO(_.of[R].consume)

  inline def fetchOneAs[R](urn: Urn | Query): ResourceApiCall[Resource.Of[R]] =
    fetchOne(urn).map(_.of[R])

  inline def fetchOneAndConsume[R](urn: Urn | Query): ResourceApiCall[Resource.InMemory[R]] =
    fetchOne(urn).flatMap(_.of[R].consume)

  inline def save[R: Resource.Addressable](
      inline addressable: R
  ): ResourceApiCall[Resource.Of[R]] =
    save(addressable.asJsonResource).map(_.of[R])

  inline def save[R](
      inline typedResource: Resource.Of[R]
  ): ResourceApiCall[Resource] =
    save(typedResource.asJsonResource)

  inline def fetchOneRel(urn: Urn, relType: String): ResourceApiCall[Resource] =
    fetchRel(urn, relType).runHead.someOrFail(ResourceError.NotFoundError(urn, None))

  inline def fetchRelAs[R](urn: Urn, relType: String): ResourceStream[Resource.Of[R]] =
    fetchRel(urn, relType).map(_.of[R])

  inline def fetchRelAndConsume[R](urn: Urn, relType: String): ResourceStream[Resource.InMemory[R]] =
    fetchRelAs[R](urn, relType).mapZIO(_.consume)

  inline def fetchOneRelAs[R](
      urn: Urn,
      relType: String
  ): ResourceApiCall[Resource.Of[R]] =
    fetchOneRel(urn, relType).map(_.of[R])

  inline def fetchOneRelAndConsume[R](
      urn: Urn,
      relType: String
  ): ResourceApiCall[Resource.InMemory[R]] =
    fetchOneRel(urn, relType).flatMap(_.of[R].consume)

object ResourceStore:

  type WithResourceStore[R] = ZIO[ResourceStore, ResourceError, R]
  type WithResourceStreamStore[R] = ZStream[ResourceStore, ResourceError, R]

  inline def withStore[R](f: ResourceStore => WithResourceStore[R]) = ZIO.service[ResourceStore].flatMap(f)
  inline def withStreamStore[R](f: ResourceStore => WithResourceStreamStore[R]) =
    ZStream.service[ResourceStore].flatMap(f)

  def fetch(urn: Urn | Query): WithResourceStreamStore[Resource] = withStreamStore(_.fetch(urn))

  def fetchOne(urn: Urn | Query): WithResourceStore[Resource] = withStore(_.fetchOne(urn))

  inline def fetchAs[R](urn: Urn | Query): WithResourceStreamStore[Resource.Of[R]] = withStreamStore(
    _.fetchAs[R](urn)
  )
  inline def fetchAndConsume[R](urn: Urn | Query): WithResourceStreamStore[Resource.InMemory[R]] =
    withStreamStore(
      _.fetchAndConsume(urn)
    )

  inline def fetchOneAs[R](urn: Urn | Query): WithResourceStore[Resource.Of[R]] = withStore(
    _.fetchOneAs[R](urn)
  )

  inline def fetchOneAndConsume[R](urn: Urn | Query): WithResourceStore[Resource.InMemory[R]] = withStore(
    _.fetchOneAndConsume(urn)
  )

  def save(resource: Resource): WithResourceStore[Resource] =
    withStore(_.save(resource))

  inline def save[R: Resource.Addressable](
      inline addressable: R
  ): WithResourceStore[Resource.Of[R]] =
    withStore(_.save(addressable))

  inline def save[R](
      inline typedResource: Resource.Of[R]
  ): WithResourceStore[Resource] =
    withStore(_.save(typedResource))

  inline def fetchOneRel(urn: Urn, relType: String): WithResourceStore[Resource] =
    withStore(_.fetchOneRel(urn, relType))

  inline def fetchRelAs[R: Resource.Addressable](
      urn: Urn,
      relType: String
  ): WithResourceStreamStore[Resource.Of[R]] =
    withStreamStore(_.fetchRelAs[R](urn, relType))

  inline def fetchRelAndConsume[R: Resource.Addressable](
      urn: Urn,
      relType: String
  ): WithResourceStreamStore[Resource.InMemory[R]] =
    withStreamStore(_.fetchRelAndConsume(urn, relType))

  inline def transaction[R](body: ResourceStore => ResourceApiCall[R]): WithResourceStore[R] =
    for
      service <- ZIO.service[ResourceStore]
      result <- service.transaction(body)
    yield result

  inline def fetchOneRelAs[R: Resource.Addressable](
      urn: Urn,
      relType: String
  ): WithResourceStore[Resource.Of[R]] =
    withStore(_.fetchOneRelAs[R](urn, relType))

  inline def fetchOneRelAndConsume[R: Resource.Addressable](
      urn: Urn,
      relType: String
  ): WithResourceStore[Resource.InMemory[R]] = withStore(_.fetchOneRelAndConsume(urn, relType))

  def delete(urn: Urn): WithResourceStore[Resource] = withStore(_.delete(urn))

  def link(leftUrn: Urn, relType: String, rightUrn: Urn): WithResourceStore[Unit] =
    withStore(_.link(leftUrn, relType, rightUrn))

  def unlink(leftUrn: Urn, relType: String, rightUrn: Urn): WithResourceStore[Unit] =
    withStore(_.unlink(leftUrn, relType, rightUrn))

  def fetchRel(urn: Urn, relType: String): WithResourceStreamStore[Resource] =
    withStreamStore(_.fetchRel(urn, relType))

  extension [R, A](resourceIO: ZIO[R, ResourceError, Resource.Of[A]])
    def body: ZIO[R, ResourceError, A] =
      resourceIO.flatMap(_.body)

  extension [R, E, Out](resourceIO: ZIO[R, ResourceError, Out])
    def ifNotFound(f: ResourceError.NotFoundError => ZIO[R, E, Out]): ZIO[R, E | ResourceError, Out] =
      resourceIO.catchSome { case e: ResourceError.NotFoundError => f(e) }

  extension [R, Out](inline resourceIO: ZIO[R & ResourceStore, ResourceError, Resource.Of[Out]])
    inline def saveIfNotFound(inline alternativeResource: => Out)(using
        Addressable[Out]
    ): ZIO[R & ResourceStore, ResourceError, Resource.Of[Out]] =
      resourceIO.ifNotFound(_ => ResourceStore.save(alternativeResource))

  trait InMemoryStore extends ResourceStore:

    private var storeMap: collection.mutable.Map[Urn, Resource] = collection.mutable.Map.empty
    private var linksMap: collection.mutable.Map[Urn, collection.mutable.Map[String, Chunk[Resource]]] =
      collection.mutable.Map.empty

    def resourceModel: ResourceModel = ResourceModel("in-mem", Map.empty)

    def fetch(urn: Urn | Query): ResourceStream[Resource] =
      urn match
        case urn: Urn =>
          ZStream.fromZIO(ZIO.fromOption(storeMap.get(urn)).orElseFail(ResourceError.NotFoundError(urn)))
        case _: Query =>
          ZStream.fail(
            ResourceError.UnderlinedError(
              new NotImplementedError("Query is not implemented for in memory store")
            )
          )

    def save(resource: Resource): ResourceApiCall[Resource] =
      ZIO.fromOption(storeMap.put(resource.urn, resource)).orElse(ZIO.succeed(resource))

    def delete(urn: Urn): ResourceApiCall[Resource] =
      ZIO.succeed {
        linksMap
          .mapValuesInPlace((_, rels) => rels.mapValuesInPlace((_, relRes) => relRes.filter(_.urn != urn)))
          .remove(urn)
      } *>
        ZIO.fromOption(storeMap.remove(urn)).orElseFail(ResourceError.NotFoundError(urn))

    def link(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit] =
      for
        _ <- ZIO.fromOption(storeMap.get(leftUrn)).orElseFail(ResourceError.NotFoundError(leftUrn))
        rightResource <-
          ZIO
            .fromOption(storeMap.get(rightUrn))
            .orElseFail(ResourceError.NotFoundError(rightUrn))
        _ <-
          ZIO.succeed:
            val relatedMap = linksMap.getOrElseUpdate(leftUrn, collection.mutable.Map.empty)
            val existingLinks = relatedMap.getOrElseUpdate(relType, Chunk.empty)
            relatedMap.put(relType, existingLinks :+ rightResource)
      yield ()

    def unlink(leftUrn: Urn, relType: String, rightUrn: Urn): ResourceApiCall[Unit] =
      for
        _ <- ZIO.fromOption(storeMap.get(leftUrn)).orElseFail(ResourceError.NotFoundError(leftUrn))
        rightResource <-
          ZIO
            .fromOption(storeMap.get(rightUrn))
            .orElseFail(ResourceError.NotFoundError(rightUrn))

        _ <-
          ZIO.succeed:
            val relatedMap = linksMap.getOrElseUpdate(leftUrn, collection.mutable.Map.empty)
            val existingLinks = relatedMap.getOrElseUpdate(relType, Chunk.empty)
            relatedMap.put(relType, existingLinks.diff(Chunk(rightResource)))
      yield ()

    def fetchRel(urn: Urn, relType: String): ResourceStream[Resource] =
      linksMap.get(urn).map(_.get(relType)) match
        case Some(Some(rels)) => ZStream.fromChunk(rels)
        case _ =>
          ZStream.fail(
            ResourceError
              .NotFoundError(urn, Some(new Throwable(s"Rel type $relType not found for urn: $urn")))
          )

        // unsafe implementation only used for testing
    def transaction[R](
        body: ResourceStore => io.funkode.resource.output.ResourceApiCall[R]
    ): ResourceApiCall[R] =
      val savedStoreMap = storeMap.clone
      val savedLinksMap = linksMap.clone

      body(this).catchAll(e =>
        ZIO.succeed {
          this.storeMap = savedStoreMap
          this.linksMap = savedLinksMap
        } *> ZIO.fail(e)
      )

  def inMemory: ZLayer[Any, ResourceError, ResourceStore] = ZLayer(ZIO.succeed(new InMemoryStore {}))
