/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.data;

import java.io.Serializable;
import java.util.Collection;

import com.vaadin.server.data.DataProvider;

/**
 * A generic interface for components that show a list of data.
 *
 * @author Vaadin Ltd.
 *
 * @param <T>
 *            the item data type
 * @since 8.0
 */
public interface Listing<T> extends Serializable {

    /**
     * Returns the source of data items used by this listing.
     *
     * @return the data provider, not null
     */
    DataProvider<T, ?> getDataProvider();

    /**
     * Sets the data provider for this listing. The data provider is queried for
     * displayed items as needed.
     *
     * @param dataProvider
     *            the data provider, not null
     */
    void setDataProvider(DataProvider<T, ?> dataProvider);

    /**
     * Sets the collection of data items of this listing.
     *
     * @param items
     *            the data items to display, not null
     *
     */
    default void setItems(Collection<T> items) {
        setDataProvider(DataProvider.create(items));
    }

    /**
     * Sets the data items of this listing.
     *
     * @param items
     *            the data items to display
     */
    default void setItems(@SuppressWarnings("unchecked") T... items) {
        setDataProvider(DataProvider.create(items));
    }

}
