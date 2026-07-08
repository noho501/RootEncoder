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
 * Receives structured [DebugEvent] instances emitted by the library.
 *
 * Register with [com.pedro.library.base.StreamBase.setDebugListener] and remove
 * with [com.pedro.library.base.StreamBase.removeDebugListener].
 *
 * The library makes no guarantee about which thread the callback is invoked on;
 * the application is responsible for any required thread marshalling.
 */
fun interface DebugListener {
  fun onDebugEvent(event: DebugEvent)
}
