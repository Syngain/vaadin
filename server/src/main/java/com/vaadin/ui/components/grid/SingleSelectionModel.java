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
package com.vaadin.ui.components.grid;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.vaadin.data.SelectionModel;
import com.vaadin.event.selection.SingleSelectionEvent;
import com.vaadin.event.selection.SingleSelectionListener;
import com.vaadin.shared.Registration;
import com.vaadin.shared.data.DataCommunicatorConstants;
import com.vaadin.shared.data.selection.SelectionServerRpc;
import com.vaadin.ui.Component;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Grid.AbstractGridExtension;
import com.vaadin.ui.Grid.GridSelectionModel;
import com.vaadin.ui.SingleSelect;
import com.vaadin.util.ReflectTools;

import elemental.json.JsonObject;

/**
 * Single selection model for grid.
 *
 * @author Vaadin Ltd.
 * @since 8.0
 *
 * @param <T>
 *            the type of the selected item in grid.
 */
public class SingleSelectionModel<T> extends AbstractGridExtension<T>
        implements GridSelectionModel<T>, SelectionModel.Single<T> {

    private static final Method SELECTION_CHANGE_METHOD = ReflectTools
            .findMethod(SingleSelectionListener.class, "accept",
                    SingleSelectionEvent.class);

    private final Grid<T> grid;
    private T selectedItem = null;

    /**
     * Constructs a new single selection model for the given grid.
     *
     * @param grid
     *            the grid to bind the selection model into
     */
    public SingleSelectionModel(Grid<T> grid) {
        this.grid = grid;
        extend(grid);
        registerRpc(new SelectionServerRpc() {

            @Override
            public void select(String key) {
                setSelectedFromClient(key);
            }

            @Override
            public void deselect(String key) {
                if (isKeySelected(key)) {
                    setSelectedFromClient(null);
                }
            }
        });
    }

    /**
     * Adds a selection change listener to this select. The listener is called
     * when the value of this select is changed either by the user or
     * programmatically.
     *
     * @param listener
     *            the value change listener, not null
     * @return a registration for the listener
     */
    public Registration addSelectionChangeListener(
            SingleSelectionListener<T> listener) {
        addListener(SingleSelectionEvent.class, listener,
                SELECTION_CHANGE_METHOD);
        return () -> removeListener(SingleSelectionEvent.class, listener);
    }

    @Override
    public Optional<T> getSelectedItem() {
        return Optional.ofNullable(selectedItem);
    }

    @Override
    public void deselect(T item) {
        Objects.requireNonNull(item, "deselected item cannot be null");
        if (isSelected(item)) {
            setSelectedFromServer(null);
        }
    }

    @Override
    public void select(T item) {
        Objects.requireNonNull(item, "selected item cannot be null");
        setSelectedFromServer(item);
    }

    /**
     * Returns whether the given key maps to the currently selected item.
     *
     * @param key
     *            the key to test or {@code null} to test whether nothing is
     *            selected
     * @return {@code true} if the key equals the key of the currently selected
     *         item (or {@code null} if no selection), {@code false} otherwise.
     */
    protected boolean isKeySelected(String key) {
        return Objects.equals(key, getSelectedKey());
    }

    /**
     * Returns the communication key of the selected item or {@code null} if no
     * item is selected.
     *
     * @return the key of the selected item if any, {@code null} otherwise.
     */
    protected String getSelectedKey() {
        return itemToKey(selectedItem);
    }

    /**
     * Sets the selected item based on the given communication key. If the key
     * is {@code null}, clears the current selection if any.
     *
     * @param key
     *            the key of the selected item or {@code null} to clear
     *            selection
     */
    protected void doSetSelectedKey(String key) {
        if (selectedItem != null) {
            grid.getDataCommunicator().refresh(selectedItem);
        }
        selectedItem = getData(key);
        if (selectedItem != null) {
            grid.getDataCommunicator().refresh(selectedItem);
        }
    }

    /**
     * Sets the selection based on a client request. Does nothing if the select
     * component is {@linkplain Component#isReadOnly()} or if the selection
     * would not change. Otherwise updates the selection and fires a selection
     * change event with {@code isUserOriginated == true}.
     *
     * @param key
     *            the key of the item to select or {@code null} to clear
     *            selection
     */
    protected void setSelectedFromClient(String key) {
        if (isKeySelected(key)) {
            return;
        }

        doSetSelectedKey(key);
        fireEvent(
                new SingleSelectionEvent<>(grid, asSingleSelect(), true));
    }

    /**
     * Sets the selection based on server API call. Does nothing if the
     * selection would not change; otherwise updates the selection and fires a
     * selection change event with {@code isUserOriginated == false}.
     *
     * @param item
     *            the item to select or {@code null} to clear selection
     */
    protected void setSelectedFromServer(T item) {
        // TODO creates a key if item not in data provider
        String key = itemToKey(item);

        if (isSelected(item) || isKeySelected(key)) {
            return;
        }

        doSetSelectedKey(key);
        fireEvent(new SingleSelectionEvent<>(grid, asSingleSelect(),
                false));
    }

    /**
     * Returns the communication key assigned to the given item.
     *
     * @param item
     *            the item whose key to return
     * @return the assigned key
     */
    protected String itemToKey(T item) {
        if (item == null) {
            return null;
        } else {
            // TODO creates a key if item not in data provider
            return grid.getDataCommunicator().getKeyMapper().key(item);
        }
    }

    @Override
    public Set<T> getSelectedItems() {
        if (selectedItem != null) {
            return new HashSet<>(Arrays.asList(selectedItem));
        } else {
            return Collections.emptySet();
        }
    }

    @Override
    public void generateData(T item, JsonObject jsonObject) {
        if (isSelected(item)) {
            jsonObject.put(DataCommunicatorConstants.SELECTED, true);
        }
    }

    @Override
    public void remove() {
        // when selection model changes, firing an event for selection change
        // event fired before removing so that parent is still intact (in case
        // needed)
        selectedItem = null;
        fireEvent(new SingleSelectionEvent<>(grid, asSingleSelect(),
                false));

        super.remove();
    }

    /**
     * Gets a wrapper for using this grid as a single select in a binder.
     *
     * @return a single select wrapper for grid
     */
    public SingleSelect<T> asSingleSelect() {
        return new SingleSelect<T>() {

            @Override
            public void setValue(T value) {
                SingleSelectionModel.this.setSelectedFromServer(value);
            }

            @Override
            public T getValue() {
                return SingleSelectionModel.this.getSelectedItem().orElse(null);
            }

            @Override
            public Registration addValueChangeListener(
                    com.vaadin.data.HasValue.ValueChangeListener<T> listener) {
                return SingleSelectionModel.this.addSelectionChangeListener(
                        event -> listener.accept(event));
            }

            @Override
            public void setRequiredIndicatorVisible(
                    boolean requiredIndicatorVisible) {
                // TODO support required indicator when grid is used in binder ?
                throw new UnsupportedOperationException(
                        "Required indicator is not supported for Grid.");
            }

            @Override
            public boolean isRequiredIndicatorVisible() {
                throw new UnsupportedOperationException(
                        "Required indicator is not supported for Grid.");
            }

            @Override
            public void setReadOnly(boolean readOnly) {
                // TODO support read only when grid is used in binder ?
                throw new UnsupportedOperationException(
                        "Read only is not supported for Grid.");
            }

            @Override
            public boolean isReadOnly() {
                throw new UnsupportedOperationException(
                        "Read only is not supported for Grid.");
            }
        };
    }
}
