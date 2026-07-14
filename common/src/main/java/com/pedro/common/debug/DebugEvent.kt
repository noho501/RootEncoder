/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.common.debug

/**
 * A structured debug event emitted by the library to help investigate device-specific issues.
 *
 * The library only emits events; it is the application's responsibility to decide whether
 * to ignore, display, save, or upload them.
 *
 * @param timestamp Elapsed-realtime milliseconds when the event was created
 *                  (see [android.os.SystemClock.elapsedRealtime]).
 * @param level     Severity of the event.
 * @param category  Functional area that produced the event.
 * @param event     Short human-readable description of what happened.
 * @param payload   Optional key/value pairs carrying additional structured data.
 */
data class DebugEvent(
  val timestamp: Long,
  val level: DebugLevel,
  val category: DebugCategory,
  val event: String,
  val payload: Map<String, Any> = emptyMap()
)
