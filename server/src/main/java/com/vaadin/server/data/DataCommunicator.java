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
package com.vaadin.server.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.server.AbstractExtension;
import com.vaadin.server.KeyMapper;
import com.vaadin.server.SerializablePredicate;
import com.vaadin.shared.Range;
import com.vaadin.shared.Registration;
import com.vaadin.shared.data.DataCommunicatorClientRpc;
import com.vaadin.shared.data.DataCommunicatorConstants;
import com.vaadin.shared.data.DataRequestRpc;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;

/**
 * DataProvider base class. This class is the base for all DataProvider
 * communication implementations. It uses {@link DataGenerator}s to write
 * {@link JsonObject}s representing each data object to be sent to the
 * client-side.
 *
 * @since 8.0
 */
public class DataCommunicator<T> extends AbstractExtension {

    private Registration dataProviderUpdateRegistration;

    /**
     * Simple implementation of collection data provider communication. All data
     * is sent by server automatically and no data is requested by client.
     */
    protected class SimpleDataRequestRpc implements DataRequestRpc {

        @Override
        public void requestRows(int firstRowIndex, int numberOfRows,
                int firstCachedRowIndex, int cacheSize) {
            pushRows = Range.withLength(firstRowIndex, numberOfRows);
            markAsDirty();
        }

        @Override
        public void dropRows(JsonArray keys) {
            for (int i = 0; i < keys.length(); ++i) {
                handler.dropActiveData(keys.getString(i));
            }
        }
    }

    /**
     * A class for handling currently active data and dropping data that is no
     * longer needed. Data tracking is based on key string provided by
     * {@link DataKeyMapper}.
     * <p>
     * When the {@link DataCommunicator} is pushing new data to the client-side
     * via {@link DataCommunicator#pushData(long, Collection)},
     * {@link #addActiveData(Collection)} and {@link #cleanUp(Collection)} are
     * called with the same parameter. In the clean up method any dropped data
     * objects that are not in the given collection will be cleaned up and
     * {@link DataGenerator#destroyData(Object)} will be called for them.
     */
    protected class ActiveDataHandler
            implements Serializable, DataGenerator<T> {

        /**
         * Set of key strings for currently active data objects
         */
        private final Set<String> activeData = new HashSet<>();

        /**
         * Set of key strings for data objects dropped on the client. This set
         * is used to clean up old data when it's no longer needed.
         */
        private final Set<String> droppedData = new HashSet<>();

        /**
         * Adds given objects as currently active objects.
         *
         * @param dataObjects
         *            collection of new active data objects
         */
        public void addActiveData(Stream<T> dataObjects) {
            dataObjects.map(getKeyMapper()::key)
                    .filter(key -> !activeData.contains(key))
                    .forEach(activeData::add);
        }

        /**
         * Executes the data destruction for dropped data that is not sent to
         * the client. This method takes most recently sent data objects in a
         * collection. Doing the clean up like this prevents the
         * {@link ActiveDataHandler} from creating new keys for rows that were
         * dropped but got re-requested by the client-side. In the case of
         * having all data at the client, the collection should be all the data
         * in the back end.
         *
         * @param dataObjects
         *            collection of most recently sent data to the client
         */
        public void cleanUp(Stream<T> dataObjects) {
            Collection<String> keys = dataObjects.map(getKeyMapper()::key)
                    .collect(Collectors.toSet());

            // Remove still active rows that were dropped by the client
            droppedData.removeAll(keys);
            // Do data clean up for object no longer needed.
            dropData(droppedData);
            droppedData.clear();
        }

        /**
         * Marks a data object identified by given key string to be dropped.
         *
         * @param key
         *            key string
         */
        public void dropActiveData(String key) {
            if (activeData.contains(key)) {
                droppedData.add(key);
            }
        }

        /**
         * Returns the collection of all currently active data.
         *
         * @return collection of active data objects
         */
        public Collection<T> getActiveData() {
            HashSet<T> hashSet = new HashSet<>();
            for (String key : activeData) {
                hashSet.add(getKeyMapper().get(key));
            }
            return hashSet;
        }

        @Override
        public void generateData(T data, JsonObject jsonObject) {
            // Write the key string for given data object
            jsonObject.put(DataCommunicatorConstants.KEY,
                    getKeyMapper().key(data));
        }

        @Override
        public void destroyData(T data) {
            // Remove from active data set
            activeData.remove(getKeyMapper().key(data));
            // Drop the registered key
            getKeyMapper().remove(data);
        }
    }

    private final Collection<DataGenerator<T>> generators = new LinkedHashSet<>();
    private final ActiveDataHandler handler = new ActiveDataHandler();

    private DataProvider<T, ?> dataProvider = DataProvider.create();
    private final DataKeyMapper<T> keyMapper;

    private boolean reset = false;
    private final Set<T> updatedData = new HashSet<>();
    private Range pushRows = Range.withLength(0, 40);

    private Comparator<T> inMemorySorting;
    private SerializablePredicate<T> inMemoryFilter;
    private final List<SortOrder<String>> backEndSorting = new ArrayList<>();
    private final DataCommunicatorClientRpc rpc;

    public DataCommunicator() {
        addDataGenerator(handler);
        rpc = getRpcProxy(DataCommunicatorClientRpc.class);
        registerRpc(createRpc());
        keyMapper = createKeyMapper();
    }

    @Override
    public void attach() {
        super.attach();
        attachDataProviderListener();
    }

    @Override
    public void detach() {
        super.detach();
        detachDataProviderListener();
    }

    /**
     * Initially and in the case of a reset all data should be pushed to the
     * client.
     */
    @Override
    public void beforeClientResponse(boolean initial) {
        super.beforeClientResponse(initial);

        if (getDataProvider() == null) {
            return;
        }

        // FIXME: Sorting and Filtering with Backend
        Set<Object> filters = Collections.emptySet();

        if (initial || reset) {
            int dataProviderSize;
            if (getDataProvider().isInMemory() && inMemoryFilter != null) {
                dataProviderSize = (int) getDataProvider().fetch(new Query())
                        .filter(inMemoryFilter).count();
            } else {
                dataProviderSize = getDataProvider().size(new Query(filters));
            }
            rpc.reset(dataProviderSize);
        }

        if (!pushRows.isEmpty()) {
            int offset = pushRows.getStart();
            int limit = pushRows.length();

            Stream<T> rowsToPush;

            if (getDataProvider().isInMemory()) {
                // We can safely request all the data when in memory
                rowsToPush = getDataProvider().fetch(new Query());
                if (inMemoryFilter != null) {
                    rowsToPush = rowsToPush.filter(inMemoryFilter);
                }
                if (inMemorySorting != null) {
                    rowsToPush = rowsToPush.sorted(inMemorySorting);
                }
                rowsToPush = rowsToPush.skip(offset).limit(limit);
            } else {
                Query query = new Query(offset, limit, backEndSorting, filters);
                rowsToPush = getDataProvider().fetch(query);
            }
            pushData(offset, rowsToPush);
        }

        if (!updatedData.isEmpty()) {
            JsonArray dataArray = Json.createArray();
            int i = 0;
            for (T data : updatedData) {
                dataArray.set(i++, getDataObject(data));
            }
            rpc.updateData(dataArray);
        }

        pushRows = Range.withLength(0, 0);
        reset = false;
        updatedData.clear();
    }

    /**
     * Adds a data generator to this data communicator. Data generators can be
     * used to insert custom data to the rows sent to the client. If the data
     * generator is already added, does nothing.
     *
     * @param generator
     *            the data generator to add, not null
     */
    public void addDataGenerator(DataGenerator<T> generator) {
        Objects.requireNonNull(generator, "generator cannot be null");
        generators.add(generator);
    }

    /**
     * Removes a data generator from this data communicator. If there is no such
     * data generator, does nothing.
     *
     * @param generator
     *            the data generator to remove, not null
     */
    public void removeDataGenerator(DataGenerator<T> generator) {
        Objects.requireNonNull(generator, "generator cannot be null");
        generators.remove(generator);
    }

    /**
     * Gets the {@link DataKeyMapper} used by this {@link DataCommunicator}. Key
     * mapper can be used to map keys sent to the client-side back to their
     * respective data objects.
     *
     * @return key mapper
     */
    public DataKeyMapper<T> getKeyMapper() {
        return keyMapper;
    }

    /**
     * Sends given collection of data objects to the client-side.
     *
     * @param firstIndex
     *            first index of pushed data
     * @param data
     *            data objects to send as an iterable
     */
    protected void pushData(int firstIndex, Stream<T> data) {
        JsonArray dataArray = Json.createArray();

        int i = 0;
        List<T> collected = data.collect(Collectors.toList());
        for (T item : collected) {
            dataArray.set(i++, getDataObject(item));
        }

        rpc.setData(firstIndex, dataArray);
        handler.addActiveData(collected.stream());
        handler.cleanUp(collected.stream());
    }

    /**
     * Creates the JsonObject for given data object. This method calls all data
     * generators for it.
     *
     * @param data
     *            data object to be made into a json object
     * @return json object representing the data object
     */
    protected JsonObject getDataObject(T data) {
        JsonObject dataObject = Json.createObject();

        for (DataGenerator<T> generator : generators) {
            generator.generateData(data, dataObject);
        }

        return dataObject;
    }

    /**
     * Drops data objects identified by given keys from memory. This will invoke
     * {@link DataGenerator#destroyData} for each of those objects.
     *
     * @param droppedKeys
     *            collection of dropped keys
     */
    private void dropData(Collection<String> droppedKeys) {
        for (String key : droppedKeys) {
            assert key != null : "Bookkeepping failure. Dropping a null key";

            T data = getKeyMapper().get(key);
            assert data != null : "Bookkeepping failure. No data object to match key";

            for (DataGenerator<T> g : generators) {
                g.destroyData(data);
            }
        }
    }

    /**
     * Informs the DataProvider that the collection has changed.
     */
    public void reset() {
        if (reset) {
            return;
        }

        reset = true;
        markAsDirty();
    }

    /**
     * Informs the DataProvider that a data object has been updated.
     *
     * @param data
     *            updated data object
     */
    public void refresh(T data) {
        if (!handler.getActiveData().contains(data)) {
            // Item is not currently available at the client-side
            return;
        }

        if (updatedData.isEmpty()) {
            markAsDirty();
        }

        updatedData.add(data);
    }

    /**
     * Sets the {@link Predicate} to use with in-memory filtering.
     *
     * @param predicate
     *            predicate used to filter data
     */
    public void setInMemoryFilter(SerializablePredicate<T> predicate) {
        inMemoryFilter = predicate;
        reset();
    }

    /**
     * Sets the {@link Comparator} to use with in-memory sorting.
     *
     * @param comparator
     *            comparator used to sort data
     */
    public void setInMemorySorting(Comparator<T> comparator) {
        inMemorySorting = comparator;
        reset();
    }

    /**
     * Sets the {@link SortOrder}s to use with backend sorting.
     *
     * @param sortOrder
     *            list of sort order information to pass to a query
     */
    public void setBackEndSorting(List<SortOrder<String>> sortOrder) {
        backEndSorting.clear();
        backEndSorting.addAll(sortOrder);
        reset();
    }

    /**
     * Creates a {@link DataKeyMapper} to use with this DataCommunicator.
     * <p>
     * This method is called from the constructor.
     *
     * @return key mapper
     */
    protected DataKeyMapper<T> createKeyMapper() {
        return new KeyMapper<>();
    }

    /**
     * Creates a {@link DataRequestRpc} used with this {@link DataCommunicator}.
     * <p>
     * This method is called from the constructor.
     *
     * @return data request rpc implementation
     */
    protected DataRequestRpc createRpc() {
        return new SimpleDataRequestRpc();
    }

    /**
     * Gets the current data provider from this DataCommunicator.
     *
     * @return the data provider
     */
    public DataProvider<T, ?> getDataProvider() {
        return dataProvider;
    }

    /**
     * Sets the current data provider for this DataCommunicator.
     *
     * @param dataProvider
     *            the data provider to set, not null
     */
    public void setDataProvider(DataProvider<T, ?> dataProvider) {
        Objects.requireNonNull(dataProvider, "data provider cannot be null");
        this.dataProvider = dataProvider;
        detachDataProviderListener();
        if (isAttached()) {
            attachDataProviderListener();
        }
        reset();
    }

    private void attachDataProviderListener() {
        dataProviderUpdateRegistration = getDataProvider()
                .addDataProviderListener(event -> reset());
    }

    private void detachDataProviderListener() {
        if (dataProviderUpdateRegistration != null) {
            dataProviderUpdateRegistration.remove();
            dataProviderUpdateRegistration = null;
        }
    }
}
