/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazonaws.QueryEventListener;

import com.facebook.presto.spi.Plugin;
import com.facebook.presto.spi.eventlistener.EventListenerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueryEventListenerPlugin
        implements Plugin
{
    public Iterable<EventListenerFactory> getEventListenerFactories()
    {
        EventListenerFactory listenerFactory = new QueryEventListenerFactory();
        List<EventListenerFactory> listenerFactoryList = new ArrayList<EventListenerFactory>();
        listenerFactoryList.add(listenerFactory);
        List<EventListenerFactory> immutableList = Collections.unmodifiableList(listenerFactoryList);

        return immutableList;
    }
}