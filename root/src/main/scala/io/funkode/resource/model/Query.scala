/*
 * Copyright 2023 Carlos Verdes
 *
 * SPDX-License-Identifier: MIT
 */

package io.funkode.resource.model

import io.funkode.velocypack.VPack

enum Query:
  case Aql(query: String, bindVars: Option[VPack.VObject] = None)
