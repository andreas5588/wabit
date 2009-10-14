/*
 * Copyright (c) 2009, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.wabit.dao;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.olap4j.metadata.Cube;
import org.olap4j.query.Selection.Operator;

import ca.sqlpower.query.Item;
import ca.sqlpower.query.QueryImpl;
import ca.sqlpower.query.SQLGroupFunction;
import ca.sqlpower.query.SQLJoin;
import ca.sqlpower.query.SQLObjectItem;
import ca.sqlpower.query.StringItem;
import ca.sqlpower.query.TableContainer;
import ca.sqlpower.query.QueryImpl.OrderByArgument;
import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sql.Olap4jDataSource;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.wabit.ObjectDependentException;
import ca.sqlpower.wabit.QueryCache;
import ca.sqlpower.wabit.WabitColumnItem;
import ca.sqlpower.wabit.WabitConstantItem;
import ca.sqlpower.wabit.WabitConstantsContainer;
import ca.sqlpower.wabit.WabitDataSource;
import ca.sqlpower.wabit.WabitItem;
import ca.sqlpower.wabit.WabitJoin;
import ca.sqlpower.wabit.WabitObject;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.WabitTableContainer;
import ca.sqlpower.wabit.WabitUtils;
import ca.sqlpower.wabit.WabitWorkspace;
import ca.sqlpower.wabit.dao.session.SessionPersisterSuperConverter;
import ca.sqlpower.wabit.enterprise.client.ReportTask;
import ca.sqlpower.wabit.image.WabitImage;
import ca.sqlpower.wabit.olap.OlapQuery;
import ca.sqlpower.wabit.olap.WabitOlapAxis;
import ca.sqlpower.wabit.olap.WabitOlapDimension;
import ca.sqlpower.wabit.olap.WabitOlapExclusion;
import ca.sqlpower.wabit.olap.WabitOlapInclusion;
import ca.sqlpower.wabit.olap.WabitOlapSelection;
import ca.sqlpower.wabit.report.CellSetRenderer;
import ca.sqlpower.wabit.report.ChartRenderer;
import ca.sqlpower.wabit.report.ColumnInfo;
import ca.sqlpower.wabit.report.ContentBox;
import ca.sqlpower.wabit.report.Guide;
import ca.sqlpower.wabit.report.HorizontalAlignment;
import ca.sqlpower.wabit.report.ImageRenderer;
import ca.sqlpower.wabit.report.Label;
import ca.sqlpower.wabit.report.Layout;
import ca.sqlpower.wabit.report.Page;
import ca.sqlpower.wabit.report.Report;
import ca.sqlpower.wabit.report.ReportContentRenderer;
import ca.sqlpower.wabit.report.ResultSetRenderer;
import ca.sqlpower.wabit.report.Template;
import ca.sqlpower.wabit.report.VerticalAlignment;
import ca.sqlpower.wabit.report.ColumnInfo.GroupAndBreak;
import ca.sqlpower.wabit.report.Guide.Axis;
import ca.sqlpower.wabit.report.Page.PageOrientation;
import ca.sqlpower.wabit.report.ResultSetRenderer.BorderStyles;
import ca.sqlpower.wabit.report.chart.Chart;
import ca.sqlpower.wabit.report.chart.ChartColumn;
import ca.sqlpower.wabit.report.chart.ChartType;
import ca.sqlpower.wabit.report.chart.ColumnRole;
import ca.sqlpower.wabit.report.chart.LegendPosition;
import ca.sqlpower.wabit.rs.ResultSetProducer;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

/**
 * This class represents a Data Access Object for {@link WabitSession}s.
 */
public class WabitSessionPersister implements WabitPersister {

	private static final Logger logger = Logger
			.getLogger(WabitSessionPersister.class);

	/**
	 * A {@link WabitSession} to persisted objects and properties onto.
	 */
	private WabitSession session;

	/**
	 * A count of transactions, mainly to keep track of nested transactions.
	 */
	private int transactionCount = 0;

	/**
	 * Persisted property buffer, mapping of {@link WabitObject} UUIDs to each
	 * individual persisted property
	 */
	private Multimap<String, WabitObjectProperty> persistedProperties = LinkedListMultimap
			.create();

	/**
	 * Persisted {@link WabitObject} buffer, contains all the data that was
	 * passed into the persistedObject call in the order of insertion
	 */
	private List<PersistedWabitObject> persistedObjects = new LinkedList<PersistedWabitObject>();

	/**
	 * {@link WabitObject} removal buffer, mapping of {@link WabitObject} UUIDs
	 * to their parents
	 */
	private Map<String, String> objectsToRemove = new LinkedHashMap<String, String>();

	/**
	 * A class representing an individual persisted {@link WabitObject}
	 * property.
	 */
	public static class WabitObjectProperty {

		private final String uuid;
		private final String propertyName;
		private final DataType dataType;
		private final Object oldValue;
		private final Object newValue;
		private final boolean unconditional;

		/**
		 * Constructor to persist a {@link WabitObject} property, keeping track
		 * of all the parameters of the persistProperty(...) method call. These
		 * fields will be necessary for when commit() is called.
		 * 
		 * @param propertyName
		 *            The name of the property to persist
		 * @param newValue
		 *            The property value to persist
		 * @param unconditional
		 *            Whether or not to validate if oldValue matches the actual
		 *            property value before persisting
		 */
		public WabitObjectProperty(String uuid, String propertyName,
				DataType dataType, Object oldValue, Object newValue,
				boolean unconditional) {
			this.uuid = uuid;
			this.propertyName = propertyName;
			this.newValue = newValue;
			this.oldValue = oldValue;
			this.unconditional = unconditional;
			this.dataType = dataType;
		}

		/**
		 * Accessor for the property name field
		 * 
		 * @return The property name to persist upon
		 */
		public String getPropertyName() {
			return propertyName;
		}

		/**
		 * Accessor for the property value to persist
		 * 
		 * @return The property value to persist
		 */
		public Object getNewValue() {
			return newValue;
		}

		/**
		 * Accessor for the unconditional persist determinant
		 * 
		 * @return The unconditional field
		 */
		public boolean isUnconditional() {
			return unconditional;
		}

		public DataType getDataType() {
			return dataType;
		}

		public Object getOldValue() {
			return oldValue;
		}

		public String getUUID() {
			return uuid;
		}

	}

	/**
	 * A class representing an individual persisted {@link WabitObject}.
	 */
	public static class PersistedWabitObject {
		private final String parentUUID;
		private final String type;
		private final String uuid;
		private final int index;

		/**
		 * Constructor to persist a {@link WabitObject}.
		 * 
		 * @param parentUUID
		 *            The parent UUID of the {@link WabitObject} to persist
		 * @param type
		 *            The {@link WabitObject} class name
		 * @param uuid
		 *            The UUID of the {@link WabitObject} to persist
		 */
		public PersistedWabitObject(String parentUUID, String type,
				String uuid, int index) {
			this.parentUUID = parentUUID;
			this.type = type;
			this.uuid = uuid;
			this.index = index;
		}

		/**
		 * Accessor for the parent UUID field
		 * 
		 * @return The parent UUID of the object to persist
		 */
		public String getParentUUID() {
			return parentUUID;
		}

		/**
		 * Accessor for the {@link WabitObject} class name
		 * 
		 * @return The {@link WabitObject} class name
		 */
		public String getType() {
			return type;
		}

		/**
		 * Accessor for the UUID field
		 * 
		 * @return The UUID of the object to persist
		 */
		public String getUUID() {
			return uuid;
		}

		public int getIndex() {
			return index;
		}

	}

	/**
	 * This converter will do all of the converting for this persister.
	 */
	private final SessionPersisterSuperConverter converter;

	/**
	 * This root object is used to find other objects by UUID by walking the
	 * descendant tree when an object is required.
	 */
	private final WabitObject root;

	/**
	 * Creates a session persister that can update any object at or a descendant
	 * of the given session's workspace object. If the persist call to this
	 * persister is involving an object that is not the workspace or descendant
	 * of the workspace in the given session an exception will be thrown
	 * depending on the call. See the specific method being called for more
	 * information about the exceptions that will be thrown.
	 */
	public WabitSessionPersister(WabitSession session) {
		this(session, session.getWorkspace());
	}

	/**
	 * Creates a session persister that can update an object at or a descendant
	 * of the given root now. If the persist call involves an object that is
	 * outside of the scope of the root node and its descendant tree an
	 * exception will be thrown depending on the method called as the object
	 * will not be found.
	 */
	public WabitSessionPersister(WabitSession session, WabitObject root) {
		this.session = session;
		this.root = root;

		converter = new SessionPersisterSuperConverter(session, root);
	}

	/**
	 * Begins a transaction
	 */
	public void begin() {
		transactionCount++;
	}

	/**
	 * Commits the persisted {@link WabitObject}s, its properties and removals
	 */
	public void commit() throws WabitPersistenceException {
		if (transactionCount <= 0) {
			throw new WabitPersistenceException(null,
					"Commit attempted while not in a transaction");
		}

		commitObjects();
		commitProperties();
		commitRemovals();

		transactionCount--;
	}

	/**
	 * Commits the persisted {@link WabitObject}s
	 * 
	 * @throws WabitPersistenceException
	 */
	private void commitObjects() throws WabitPersistenceException {

		for (PersistedWabitObject pwo : persistedObjects) {
			String uuid = pwo.getUUID();
			String type = pwo.getType();
			WabitObject wo = null;
			WabitObject parent = WabitUtils.findByUuid(root, pwo.getParentUUID(),
					WabitObject.class);

			if (type.equals(CellSetRenderer.class.getSimpleName())) {
				OlapQuery olapQuery = (OlapQuery) converter
						.convertToComplexType(getPropertyAndRemove(uuid,
								"modifiedOlapQuery"), OlapQuery.class);
				wo = new CellSetRenderer(olapQuery);

			} else if (type.equals(Chart.class.getSimpleName())) {
				wo = new Chart();

			} else if (type.equals(ChartColumn.class.getSimpleName())) {
				String columnName = (String) getPropertyAndRemove(uuid, "name");
				ca.sqlpower.wabit.report.chart.ChartColumn.DataType dataType = (ca.sqlpower.wabit.report.chart.ChartColumn.DataType) converter
						.convertToComplexType(
								getPropertyAndRemove(uuid, "dataType"),
								ca.sqlpower.wabit.report.chart.ChartColumn.DataType.class);

				wo = new ChartColumn(columnName, dataType);

			} else if (type.equals(ChartRenderer.class.getSimpleName())) {
				Chart chart = (Chart) converter.convertToComplexType(
						getPropertyAndRemove(uuid, "chart"), Chart.class);
				wo = new ChartRenderer(chart);

			} else if (type.equals(ColumnInfo.class.getSimpleName())) {
				wo = new ColumnInfo((String) getPropertyAndRemove(uuid,
						ColumnInfo.COLUMN_ALIAS));

			} else if (type.equals(ContentBox.class.getSimpleName())) {
				wo = new ContentBox();

			} else if (type.equals(Guide.class.getSimpleName())) {
				Axis axis = (Axis) converter.convertToComplexType(
						getPropertyAndRemove(uuid, "axis"), Axis.class);
				double offset = (Double) getPropertyAndRemove(uuid, "offset");

				wo = new Guide(axis, offset);

			} else if (type.equals(ImageRenderer.class.getSimpleName())) {
				wo = new ImageRenderer();

			} else if (type.equals(Label.class.getSimpleName())) {
				wo = new Label();

			} else if (type.equals(OlapQuery.class.getSimpleName())) {
				String name = (String) getPropertyAndRemove(uuid, "name");
				String queryName = (String) getPropertyAndRemove(uuid,
						"queryName");
				String catalogName = (String) getPropertyAndRemove(uuid,
						"catalogName");
				String schemaName = (String) getPropertyAndRemove(uuid,
						"schemaName");
				String cubeName = (String) getPropertyAndRemove(uuid,
						"cubeName");
				wo = new OlapQuery(uuid, session.getContext(), name, queryName,
						catalogName, schemaName, cubeName);

			} else if (type.equals(Page.class.getSimpleName())) {
				String name = (String) getPropertyAndRemove(uuid, "name");
				int width = (Integer) getPropertyAndRemove(uuid, "width");
				int height = (Integer) getPropertyAndRemove(uuid, "height");
				PageOrientation orientation = (PageOrientation) converter
						.convertToComplexType(getPropertyAndRemove(uuid,
								"orientation"), PageOrientation.class);

				wo = new Page(name, width, height, orientation);

			} else if (type.equals(QueryCache.class.getSimpleName())) {
				wo = new QueryCache(session.getContext());

			} else if (type.equals(Report.class.getSimpleName())) {
				String name = (String) getPropertyAndRemove(uuid, "name");

				wo = new Report(name);

			} else if (type.equals(ResultSetRenderer.class.getSimpleName())) {
				String contentID = (String) getPropertyAndRemove(uuid,
						"content");

				// TODO No ResultSetRenderer object has been created yet.

			} else if (type.equals(Template.class.getSimpleName())) {
				String name = (String) getPropertyAndRemove(uuid, "name");

				wo = new Template(name);

			} else if (type.equals(WabitColumnItem.class.getSimpleName())) {
				String delegateString = (String) getPropertyAndRemove(uuid,
						"delegate");
				SQLObjectItem item = (SQLObjectItem) converter
						.convertToComplexType(delegateString,
								SQLObjectItem.class);

				wo = new WabitColumnItem(item);

			} else if (type.equals(WabitConstantsContainer.class
					.getSimpleName())) {
				wo = ((QueryCache) parent).getWabitConstantsContainer();

			} else if (type.equals(WabitConstantItem.class.getSimpleName())) {
				String delegateString = (String) getPropertyAndRemove(uuid,
						"delegate");
				StringItem item = (StringItem) converter.convertToComplexType(
						delegateString, StringItem.class);

				wo = new WabitConstantItem(item);

			} else if (type.equals(WabitDataSource.class.getSimpleName())) {
				SPDataSource spds = session.getContext().getDataSources()
						.getDataSource(
								(String) getPropertyAndRemove(uuid, "name"));

				wo = new WabitDataSource(spds);

			} else if (type.equals(WabitImage.class.getSimpleName())) {
				wo = new WabitImage();

			} else if (type.equals(WabitJoin.class.getSimpleName())) {
				Item leftItem = WabitUtils.findByUuid(root, 
						getPropertyAndRemove(uuid, "leftColumnOuterJoin")
								.toString(), WabitColumnItem.class)
						.getDelegate();
				Item rightItem = WabitUtils.findByUuid(root, 
						getPropertyAndRemove(uuid, "rightColumnOuterJoin")
								.toString(), WabitColumnItem.class)
						.getDelegate();
				wo = new WabitJoin((QueryCache) parent, new SQLJoin(leftItem,
						rightItem));

			} else if (type.equals(WabitOlapAxis.class.getSimpleName())) {
				org.olap4j.Axis axis = org.olap4j.Axis.Factory
						.forOrdinal((Integer) getPropertyAndRemove(uuid,
								"ordinal"));

				wo = new WabitOlapAxis(axis);

			} else if (type.equals(WabitOlapDimension.class.getSimpleName())) {
				String name = (String) getPropertyAndRemove(uuid, "name");

				wo = new WabitOlapDimension(name);

			} else if (type.equals(WabitOlapExclusion.class.getSimpleName())) {
				Operator operator = (Operator) converter.convertToComplexType(
						getPropertyAndRemove(uuid, "operator"), Operator.class);
				String uniqueMemberName = (String) getPropertyAndRemove(uuid,
						"uniqueMemberName");

				wo = new WabitOlapExclusion(operator, uniqueMemberName);

			} else if (type.equals(WabitOlapInclusion.class.getSimpleName())) {
				Operator operator = (Operator) converter.convertToComplexType(
						getPropertyAndRemove(uuid, "operator"), Operator.class);
				String uniqueMemberName = (String) getPropertyAndRemove(uuid,
						"uniqueMemberName");

				wo = new WabitOlapInclusion(operator, uniqueMemberName);

			} else if (type.equals(WabitTableContainer.class.getSimpleName())) {
				String delegateString = (String) getPropertyAndRemove(uuid,
						"delegate");
				TableContainer tableContainer = (TableContainer) converter
						.convertToComplexType(delegateString,
								TableContainer.class);

				wo = new WabitTableContainer(tableContainer);

			} else {
				throw new WabitPersistenceException(uuid,
						"Unknown WabitObject type: " + type);
			}

			if (wo != null) {
				wo.setUUID(uuid);
				parent.addChild(wo, parent.getChildren().size());
			}

		}

		persistedObjects.clear();
	}

	/**
	 * Retrieves a persisted property value given by the UUID of the
	 * {@link WabitObject} and its property name. The property is removed from
	 * the {@link Multimap} if found.
	 * 
	 * @param uuid
	 *            The UUID of the {@link WabitObject}
	 * @param propertyName
	 *            The persisted property name
	 * @return The persisted property value
	 */
	private Object getPropertyAndRemove(String uuid, String propertyName) {
		for (WabitObjectProperty wop : persistedProperties.get(uuid)) {
			if (wop.getPropertyName().equals(propertyName)) {
				Object value = wop.getNewValue();

				persistedProperties.remove(uuid, wop);

				return value;
			}
		}

		return null;
	}

	/**
	 * Checks to see if a {@link WabitObject} with a certain UUID exists
	 * 
	 * @param uuid
	 *            The UUID to search for
	 * @return Whether or not the {@link WabitObject} exists
	 */
	private boolean exists(String uuid) {
		if (!objectsToRemove.containsKey(uuid)) {
			for (PersistedWabitObject pwo : persistedObjects) {
				if (uuid.equals(pwo.getUUID())) {
					return true;
				}
			}
			if (WabitUtils.findByUuid(root, uuid, WabitObject.class) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Commits the persisted {@link WabitObject} property values
	 * 
	 * @throws WabitPersistenceException
	 *             Thrown if an invalid WabitObject type has been persisted into
	 *             storage. This theoretically should not occur.
	 */
	private void commitProperties() throws WabitPersistenceException {
		WabitObject wo;
		String propertyName;
		Object newValue;

		for (String uuid : persistedProperties.keySet()) {
			wo = WabitUtils.findByUuid(root, uuid, WabitObject.class);

			for (WabitObjectProperty wop : persistedProperties.get(uuid)) {
				propertyName = wop.getPropertyName();
				newValue = wop.getNewValue();

				if (isCommonProperty(propertyName)) {
					commitCommonProperty(wo, propertyName, newValue);
				} else if (wo instanceof CellSetRenderer) {
					commitCellSetRendererProperty((CellSetRenderer) wo,
							propertyName, newValue);
				} else if (wo instanceof Chart) {
					commitChartProperty((Chart) wo, propertyName, newValue);
				} else if (wo instanceof ChartColumn) {
					commitChartColumnProperty((ChartColumn) wo, propertyName,
							newValue);
				} else if (wo instanceof ChartRenderer) {
					commitChartRendererProperty((ChartRenderer) wo,
							propertyName, newValue);
				} else if (wo instanceof ColumnInfo) {
					commitColumnInfoProperty((ColumnInfo) wo, propertyName,
							newValue);
				} else if (wo instanceof ContentBox) {
					commitContentBoxProperty((ContentBox) wo, propertyName,
							newValue);
				} else if (wo instanceof Guide) {
					commitGuideProperty((Guide) wo, propertyName, newValue);
				} else if (wo instanceof ImageRenderer) {
					commitImageRendererProperty((ImageRenderer) wo,
							propertyName, newValue);
				} else if (wo instanceof Label) {
					commitLabelProperty((Label) wo, propertyName, newValue);
				} else if (wo instanceof Layout) {
					commitLayoutProperty((Layout) wo, propertyName, newValue);
				} else if (wo instanceof OlapQuery) {
					commitOlapQueryProperty((OlapQuery) wo, propertyName,
							newValue);
				} else if (wo instanceof Page) {
					commitPageProperty((Page) wo, propertyName, newValue);
				} else if (wo instanceof QueryCache) {
					commitQueryCacheProperty((QueryCache) wo, propertyName,
							newValue);
				} else if (wo instanceof ResultSetRenderer) {
					commitResultSetRendererProperty((ResultSetRenderer) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitConstantsContainer) {
					commitWabitConstantsContainerProperty(
							(WabitConstantsContainer) wo, propertyName,
							newValue);
				} else if (wo instanceof WabitDataSource) {
					commitWabitDataSourceProperty((WabitDataSource) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitOlapAxis) {
					commitWabitOlapAxisProperty((WabitOlapAxis) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitOlapDimension) {
					commitWabitOlapDimensionProperty((WabitOlapDimension) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitOlapSelection) {
					commitWabitOlapSelectionProperty((WabitOlapSelection) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitImage) {
					commitWabitImageProperty((WabitImage) wo, propertyName,
							newValue);
				} else if (wo instanceof WabitItem) {
					commitWabitItemProperty((WabitItem) wo, propertyName,
							newValue);
				} else if (wo instanceof WabitJoin) {
					commitWabitJoinProperty((WabitJoin) wo, propertyName,
							newValue);
				} else if (wo instanceof WabitTableContainer) {
					commitWabitTableContainerProperty((WabitTableContainer) wo,
							propertyName, newValue);
				} else if (wo instanceof WabitWorkspace) {
					commitWabitWorkspaceProperty((WabitWorkspace) wo,
							propertyName, newValue);
				} else {
					throw new WabitPersistenceException(uuid,
							"Invalid WabitObject of type " + wo.getClass());
				}

			}

		}

		persistedProperties.clear();

	}

	/**
	 * Commits the removal of persisted {@link WabitObject}s
	 * 
	 * @throws WabitPersistenceException
	 *             Thrown if a WabitObject could not be removed from its parent.
	 */
	private void commitRemovals() throws WabitPersistenceException {

		for (String uuid : objectsToRemove.keySet()) {
			WabitObject wo = WabitUtils.findByUuid(root, uuid, WabitObject.class);
			WabitObject parent = WabitUtils.findByUuid(root, 
					objectsToRemove.get(uuid), WabitObject.class);

			try {
				parent.removeChild(wo);
			} catch (IllegalArgumentException e) {
				throw new WabitPersistenceException(uuid, e);
			} catch (ObjectDependentException e) {
				throw new WabitPersistenceException(uuid, e);
			}
		}

		objectsToRemove.clear();
	}

	/**
	 * Persists a {@link WabitObject} given by its UUID, class name, and parent
	 * UUID
	 * 
	 * @param parentUUID
	 *            The parent UUID of the {@link WabitObject} to persist
	 * @param type
	 *            The class name of the {@link WabitObject} to persist
	 * @param uuid
	 *            The UUID of the {@link WabitObject} to persist
	 * @param index
	 *            The index of the {@link WabitObject} within its parents' list
	 *            of children
	 * 
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	public void persistObject(String parentUUID, String type, String uuid,
			int index) throws WabitPersistenceException {

		if (exists(uuid)) {
			throw new WabitPersistenceException(uuid,
					"A WabitObject with UUID " + uuid + " and type " + type 
					+ " under parent with UUID " + parentUUID + " already exists.");
		}

		PersistedWabitObject pwo = new PersistedWabitObject(parentUUID, type,
				uuid, index);

		persistedObjects.add(pwo);

		if (transactionCount == 0) {
			commitObjects();
		}

	}

	/**
	 * Persists a {@link WabitObject} property conditionally given by its object
	 * UUID, property name, property type, expected old value, and new value
	 * 
	 * @param uuid
	 *            The UUID of the {@link WabitObject} to persist the property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param propertyType
	 *            The property type
	 * @param oldValue
	 *            The expected old property value
	 * @param newValue
	 *            The new property value to persist
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	public void persistProperty(String uuid, String propertyName,
			DataType propertyType, Object oldValue, Object newValue)
			throws WabitPersistenceException {
		persistPropertyHelper(uuid, propertyName, propertyType, oldValue,
				newValue, false);
	}

	/**
	 * Persists a {@link WabitObject} property unconditionally given by its
	 * object UUID, property name, property type, and new value
	 * 
	 * @param uuid
	 *            The UUID of the {@link WabitObject} to persist the property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param propertyType
	 *            The property type
	 * @param newValue
	 *            The new property value to persist
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	public void persistProperty(String uuid, String propertyName,
			DataType propertyType, Object newValue)
			throws WabitPersistenceException {
		persistPropertyHelper(uuid, propertyName, propertyType, null, newValue,
				true);
	}

	/**
	 * Helper to persist a {@link WabitObject} property given by its object
	 * UUID, property name, property type, expected old value, and new value.
	 * This can be done either conditionally or unconditionally based on which
	 * persistProperty method called this one.
	 * 
	 * @param uuid
	 *            The UUID of the {@link WabitObject} to persist the property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param propertyType
	 *            The property type
	 * @param oldValue
	 *            The expected old property value
	 * @param newValue
	 *            The new property value to persist
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void persistPropertyHelper(String uuid, String propertyName,
			DataType propertyType, Object oldValue, Object newValue,
			boolean unconditional) throws WabitPersistenceException {
		if (!exists(uuid)) {
			throw new WabitPersistenceException(uuid, "WabitObject with UUID "
					+ uuid + " does not exist.");
		}

		Object lastPropertyValueFound = null;

		for (WabitObjectProperty wop : persistedProperties.get(uuid)) {
			if (propertyName.equals(wop.getPropertyName())) {
				lastPropertyValueFound = wop.getNewValue();
				if (wop.isUnconditional() && unconditional) {
					throw new WabitPersistenceException(
							uuid,
							"Cannot make more than one unconditional persist property call for property \"" + propertyName + "\" in the same transaction.");
				}

			}
		}

		if (lastPropertyValueFound != null) {
			if (!unconditional && !oldValue.equals(lastPropertyValueFound)) {
				throw new WabitPersistenceException(
						uuid,
						"For property \"" + propertyName 
								+ "\", the expected property value \""
								+ oldValue
								+ "\" does not match with the actual property value \""
								+ lastPropertyValueFound + "\"");
			}
		} else if (!unconditional) {
			WabitObject wo = WabitUtils.findByUuid(root, uuid,
					WabitObject.class);
			Object propertyValue = null;

			if (isCommonProperty(propertyName)) {
				propertyValue = getCommonProperty(wo, propertyName);
			} else if (wo instanceof CellSetRenderer) {
				propertyValue = getCellSetRendererProperty(
						(CellSetRenderer) wo, propertyName);
			} else if (wo instanceof Chart) {
				propertyValue = getChartProperty((Chart) wo, propertyName);
			} else if (wo instanceof ChartColumn) {
				propertyValue = getChartColumnProperty((ChartColumn) wo,
						propertyName);
			} else if (wo instanceof ChartRenderer) {
				propertyValue = getChartRendererProperty((ChartRenderer) wo,
						propertyName);
			} else if (wo instanceof ColumnInfo) {
				propertyValue = getColumnInfoProperty((ColumnInfo) wo,
						propertyName);
			} else if (wo instanceof ContentBox) {
				propertyValue = getContentBoxProperty((ContentBox) wo,
						propertyName);
			} else if (wo instanceof Guide) {
				propertyValue = getGuideProperty((Guide) wo, propertyName);
			} else if (wo instanceof ImageRenderer) {
				propertyValue = getImageRendererProperty((ImageRenderer) wo,
						propertyName);
			} else if (wo instanceof Label) {
				propertyValue = getLabelProperty((Label) wo, propertyName);
			} else if (wo instanceof Layout) {
				propertyValue = getLayoutProperty((Layout) wo, propertyName);
			} else if (wo instanceof OlapQuery) {
				propertyValue = getOlapQueryProperty((OlapQuery) wo,
						propertyName);
			} else if (wo instanceof Page) {
				propertyValue = getPageProperty((Page) wo, propertyName);
			} else if (wo instanceof QueryCache) {
				propertyValue = getQueryCacheProperty((QueryCache) wo,
						propertyName);
			} else if (wo instanceof ResultSetRenderer) {
				propertyValue = getResultSetRendererProperty(
						(ResultSetRenderer) wo, propertyName);
			} else if (wo instanceof WabitConstantsContainer) {
				propertyValue = getWabitConstantsContainerProperty(
						(WabitConstantsContainer) wo, propertyName);
			} else if (wo instanceof WabitDataSource) {
				propertyValue = getWabitDataSourceProperty(
						(WabitDataSource) wo, propertyName);
			} else if (wo instanceof WabitImage) {
				propertyValue = getWabitImageProperty((WabitImage) wo,
						propertyName);

				// Convert oldValue into a byte array so that it can be compared
				// with propertyValue
				final Image wabitInnerImage = ((WabitImage) wo).getImage();
				
				// We are converting the expected old value InputStream in this way because
				// we want to ensure that the conversion process is the same as the one
				// used to convert the current image into a byte array.
				oldValue = PersisterUtils.convertImageToStreamAsPNG(
						(Image) converter.convertToComplexType(
								oldValue, Image.class)).toByteArray();

			} else if (wo instanceof WabitItem) {
				propertyValue = getWabitItemProperty((WabitItem) wo,
						propertyName);
			} else if (wo instanceof WabitJoin) {
				propertyValue = getWabitJoinProperty((WabitJoin) wo,
						propertyName);
			} else if (wo instanceof WabitOlapAxis) {
				propertyValue = getWabitOlapAxisProperty((WabitOlapAxis) wo,
						propertyName);
			} else if (wo instanceof WabitOlapDimension) {
				propertyValue = getWabitOlapDimensionProperty(
						(WabitOlapDimension) wo, propertyName);
			} else if (wo instanceof WabitOlapSelection) {
				propertyValue = getWabitOlapSelectionProperty(
						(WabitOlapSelection) wo, propertyName);
			} else if (wo instanceof WabitTableContainer) {
				propertyValue = getWabitTableContainerProperty(
						(WabitTableContainer) wo, propertyName);
			} else if (wo instanceof WabitWorkspace) {
				propertyValue = getWabitWorkspaceProperty((WabitWorkspace) wo,
						propertyName);
			} else if (wo instanceof ReportTask) {
				propertyValue = getReportTaskProperty((ReportTask) wo,
						propertyName);
			} else {
				throw new WabitPersistenceException(uuid, "Invalid WabitObject type " + wo.getClass());
			}

			if ((oldValue == null && propertyValue != null) ||
					(oldValue != null && !oldValue.equals(propertyValue))) {
				throw new WabitPersistenceException(
						uuid,
						"For property \"" + propertyName + "\" on WabitObject of type "
								+ wo.getClass() + " and UUID + " + wo.getUUID() 
								+ ", the expected property value \""
								+ oldValue
								+ "\" does not match with the actual property value \""
								+ propertyValue + "\"");
			}
		}

		persistedProperties.put(uuid, new WabitObjectProperty(uuid,
				propertyName, propertyType, oldValue, newValue, unconditional));

		if (transactionCount == 0) {
			commitProperties();
		}
	}

	/**
	 * Returns a simple string for use in exceptions in multiple locations
	 * within this class. This message describes that a property cannot be found
	 * on the object. This is refactored here as a lot of methods throw an
	 * exception with a message equivalent to this one.
	 * 
	 * @param wo
	 *            The {@link WabitObject} that does not contain the given property.
	 * @param propertyName
	 *            The property we want to find on the {@link WabitObject} that
	 *            cannot be found.
	 * @return An error message for exceptions that describes the above.
	 */
	public String getWabitPersistenceExceptionMessage(WabitObject wo, String propertyName) {
		return "Cannot persist property \"" + propertyName + "\" on " + wo.getClass() 
			+ " with name \"" + wo.getName() + "\" and UUID \"" + wo.getUUID() + "\"";
	}
	
	public String getNotDefinedPropertyExceptionMessage(WabitObject wo,
			String propertyName, Object newValue) {
		return "Could not commit the property \"" + propertyName 
			+ "\" on " + wo.getClass() + " with name \"" + wo.getName() 
			+ "\" and UUID \"" + wo.getUUID() + " with new value \"" + newValue.toString()
			+ "\" which is of data type \"" + newValue.getClass() + "\""; 
	}

	/**
	 * Determines if a given property name is a common property among all
	 * {@link WabitObject}s
	 * 
	 * @param propertyName
	 *            The property name to check if it is common
	 * @return Determinant of whether the given property name is common
	 */
	private boolean isCommonProperty(String propertyName) {
		return (propertyName.equals("name") || propertyName.equals("UUID") 
				|| propertyName.equals("parent"));
	}

	/**
	 * Retrieves a common property value from a {@link WabitObject} based on the
	 * property name and converts it to something that can be passed to a
	 * persister. The only two common properties are "name" and "uuid".
	 * 
	 * @param wo
	 *            The {@link WabitObject} to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getCommonProperty(WabitObject wo, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("name")) {
			return wo.getName();
		} else if (propertyName.equals("UUID")) {
			return wo.getUUID();
		} else if (propertyName.equals("parent")) {
			return converter.convertToBasicType(wo.getParent(), DataType.REFERENCE);
		} else {
			throw new WabitPersistenceException(wo.getUUID(),
					getWabitPersistenceExceptionMessage(wo, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitObject} common property.
	 * 
	 * @param wo
	 *            The {@link WabitObject} to commit the persisted common
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitCommonProperty(WabitObject wo, String propertyName,
			Object newValue) throws WabitPersistenceException {
		if (propertyName.equals("name")) {
			wo.setName((String) newValue);
		} else if (propertyName.equals("UUID")) {
			wo.setUUID((String) newValue);
		} else if (propertyName.equals("parent")) {
			wo.setParent((WabitObject) converter.convertToComplexType(
					newValue, WabitObject.class));
			
		} else {
			throw new WabitPersistenceException(wo.getUUID(),
					getWabitPersistenceExceptionMessage(wo, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitWorkspace} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param workspace
	 *            The {@link WabitWorkspace} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitWorkspaceProperty(WabitWorkspace workspace,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("editorPanelModel")) {
			return converter.convertToBasicType(
					workspace.getEditorPanelModel(), DataType.REFERENCE);
		} else {
			throw new WabitPersistenceException(workspace.getUUID(),
					getWabitPersistenceExceptionMessage(workspace, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitWorkspace} object property.
	 * 
	 * @param workspace
	 *            The {@link WabitWorkspace} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method
	 *             or the new value could not be committed.
	 */
	private void commitWabitWorkspaceProperty(WabitWorkspace workspace,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		String uuid = workspace.getUUID();

		if (propertyName.equals("editorPanelModel")) {
			WabitObject editorPanel = (WabitObject) converter
					.convertToComplexType(newValue, WabitObject.class);

			if (editorPanel == null) {
				throw new WabitPersistenceException(uuid,
						getNotDefinedPropertyExceptionMessage(workspace, propertyName, newValue));
			}

			workspace.setEditorPanelModel(editorPanel);
		} else {
			throw new WabitPersistenceException(uuid, 
					getWabitPersistenceExceptionMessage(workspace, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitDataSource} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister. Currently, uncommon properties cannot be retrieved from this
	 * class.
	 * 
	 * @param wds
	 *            The {@link WabitDataSource} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitDataSourceProperty(WabitDataSource wds,
			String propertyName) throws WabitPersistenceException {
		throw new WabitPersistenceException(wds.getUUID(), "Invalid property: "
				+ propertyName);
	}

	/**
	 * Commits a persisted {@link WabitDataSource} property. Currently, uncommon
	 * properties cannot be persisted for this class.
	 * 
	 * @param wds
	 *            The {@link WabitDataSource} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitDataSourceProperty(WabitDataSource wds,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		throw new WabitPersistenceException(wds.getUUID(), "Invalid property: "
				+ propertyName);
	}

	/**
	 * Retrieves a property value from a {@link QueryCache} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param query
	 *            The {@link QueryCache} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getQueryCacheProperty(QueryCache query, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("zoomLevel")) {
			return query.getZoomLevel();

		} else if (propertyName.equals("streaming")) {
			return query.isStreaming();

		} else if (propertyName.equals("streamingRowLimit")) {
			return query.getStreamingRowLimit();

		} else if (propertyName.equals(QueryImpl.ROW_LIMIT)) {
			return query.getRowLimit();

		} else if (propertyName.equals(QueryImpl.GROUPING_ENABLED)) {
			return query.isGroupingEnabled();

		} else if (propertyName.equals("promptForCrossJoins")) {
			return query.getPromptForCrossJoins();

		} else if (propertyName.equals("automaticallyExecuting")) {
			return query.isAutomaticallyExecuting();

		} else if (propertyName.equals(QueryImpl.GLOBAL_WHERE_CLAUSE)) {
			return query.getGlobalWhereClause();

		} else if (propertyName.equals(QueryImpl.USER_MODIFIED_QUERY)) {
			return query.getUserModifiedQuery();

		} else if (propertyName.equals("executeQueriesWithCrossJoins")) {
			return query.getExecuteQueriesWithCrossJoins();

		} else if (propertyName.equals("dataSource")) {
			return converter.convertToBasicType(query.getWabitDataSource(),
					DataType.JDBC_DATA_SOURCE);

		} else {
			throw new WabitPersistenceException(query.getUUID(),
					getWabitPersistenceExceptionMessage(query, propertyName));
		}

	}

	/**
	 * Commits a persisted {@link QueryCache} property
	 * 
	 * @param query
	 *            The {@link QueryCache} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitQueryCacheProperty(QueryCache query,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		String uuid = query.getUUID();

		if (propertyName.equals("zoomLevel")) {
			query.setZoomLevel((Integer) newValue);

		} else if (propertyName.equals("streaming")) {
			query.setStreaming((Boolean) newValue);

		} else if (propertyName.equals("streamingRowLimit")) {
			query.setStreamingRowLimit((Integer) newValue);

		} else if (propertyName.equals(QueryImpl.ROW_LIMIT)) {
			query.setRowLimit((Integer) newValue);

		} else if (propertyName.equals(QueryImpl.GROUPING_ENABLED)) {
			query.setGroupingEnabled((Boolean) newValue);

		} else if (propertyName.equals("promptForCrossJoins")) {
			query.setPromptForCrossJoins((Boolean) newValue);

		} else if (propertyName.equals("automaticallyExecuting")) {
			query.setAutomaticallyExecuting((Boolean) newValue);

		} else if (propertyName.equals(QueryImpl.GLOBAL_WHERE_CLAUSE)) {
			query.setGlobalWhereClause((String) newValue);

		} else if (propertyName.equals(QueryImpl.USER_MODIFIED_QUERY)) {
			query.setUserModifiedQuery((String) newValue);

		} else if (propertyName.equals("executeQueriesWithCrossJoins")) {
			query.setExecuteQueriesWithCrossJoins((Boolean) newValue);

		} else if (propertyName.equals("dataSource")) {
			query.setDataSource((JDBCDataSource) converter
					.convertToComplexType(newValue, JDBCDataSource.class));

		} else {
			throw new WabitPersistenceException(uuid, "Invalid property: "
					+ propertyName);
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitConstantsContainer} object
	 * based on the property name and converts it to something that can be
	 * passed to a persister.
	 * 
	 * @param wabitConstantsContainer
	 *            The {@link WabitConstantsContainer} to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitConstantsContainerProperty(
			WabitConstantsContainer wabitConstantsContainer, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("alias")) {
			return wabitConstantsContainer.getDelegate().getAlias();

		} else if (propertyName.equals("position")) {
			return converter.convertToBasicType(wabitConstantsContainer
					.getDelegate().getPosition(), DataType.POINT2D);

		} else {
			throw new WabitPersistenceException(wabitConstantsContainer
					.getUUID(), getWabitPersistenceExceptionMessage(
							wabitConstantsContainer, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitConstantsContainer} object property
	 * 
	 * @param wabitConstantsContainer
	 *            The {@link WabitConstantsContainer} object to commit the
	 *            persisted property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitConstantsContainerProperty(
			WabitConstantsContainer wabitConstantsContainer,
			String propertyName, Object newValue)
			throws WabitPersistenceException {

		if (propertyName.equals("alias")) {
			wabitConstantsContainer.getDelegate().setAlias((String) newValue);

		} else if (propertyName.equals("position")) {
			wabitConstantsContainer.getDelegate().setPosition(
					(Point2D) converter.convertToComplexType(newValue,
							Point2D.class));

		} else {
			throw new WabitPersistenceException(wabitConstantsContainer
					.getUUID(), getWabitPersistenceExceptionMessage(
							wabitConstantsContainer, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitTableContainer} object
	 * based on the property name and converts it to something that can be
	 * passed to a persister.
	 * 
	 * @param wabitTableContainer
	 *            The {@link WabitTableContainer} to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitTableContainerProperty(
			WabitTableContainer wabitTableContainer, String propertyName)
			throws WabitPersistenceException {

		if (propertyName.equals("position")) {
			return converter.convertToBasicType(wabitTableContainer
					.getPosition(), DataType.POINT2D);

		} else if (propertyName.equals("alias")) {
			return wabitTableContainer.getAlias();

		} else {
			throw new WabitPersistenceException(wabitTableContainer.getUUID(),
					getWabitPersistenceExceptionMessage(wabitTableContainer, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitTableContainer} object property
	 * 
	 * @param wabitTableContainer
	 *            The {@link WabitTableContainer} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitTableContainerProperty(
			WabitTableContainer wabitTableContainer, String propertyName,
			Object newValue) throws WabitPersistenceException {

		if (propertyName.equals("position")) {
			wabitTableContainer.setPosition((Point2D) converter
					.convertToComplexType(newValue, Point2D.class));

		} else if (propertyName.equals("alias")) {
			wabitTableContainer.setAlias((String) newValue);

		} else {
			throw new WabitPersistenceException(wabitTableContainer.getUUID(),
					getWabitPersistenceExceptionMessage(wabitTableContainer, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitItem} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param wabitItem
	 *            The {@link WabitItem} to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitItemProperty(WabitItem wabitItem, String propertyName)
			throws WabitPersistenceException {
		String uuid = wabitItem.getUUID();
		Item item = wabitItem.getDelegate();

		if (item instanceof SQLObjectItem || item instanceof StringItem) {
			if (propertyName.equals(Item.ALIAS)) {
				return item.getAlias();

			} else if (propertyName.equals(Item.WHERE)) {
				return item.getWhere();

			} else if (propertyName.equals(Item.GROUP_BY)) {
				return converter.convertToBasicType(item.getGroupBy(),
						DataType.ENUM);

			} else if (propertyName.equals(Item.HAVING)) {
				return item.getHaving();

			} else if (propertyName.equals(Item.ORDER_BY)) {
				return converter.convertToBasicType(item.getOrderBy(),
						DataType.ENUM);

			} else if (propertyName.equals(Item.SELECTED)) {
				return item.getSelected();

			} else if (propertyName.equals(Item.WHERE)) {
				return item.getWhere();

			} else if (propertyName.equals("orderByOrdering")) {
				return item.getOrderByOrdering();

			} else if (propertyName.equals("colWidth")) {
				return item.getColumnWidth();

			} else {
				throw new WabitPersistenceException(uuid,
						getWabitPersistenceExceptionMessage(wabitItem, propertyName));
			}
		} else {
			throw new WabitPersistenceException(uuid, "Unknown WabitItem with name "
					+ wabitItem.getName());
		}
	}

	/**
	 * Commits a persisted {@link WabitItem} object property
	 * 
	 * @param wabitItem
	 *            The {@link WabitItem} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method or if an
	 *             invalid delegate is contained within this wrapper.
	 */
	private void commitWabitItemProperty(WabitItem wabitItem,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		String uuid = wabitItem.getUUID();
		Item item = wabitItem.getDelegate();

		if (item instanceof SQLObjectItem || item instanceof StringItem) {
			if (propertyName.equals(Item.ALIAS)) {
				item.setAlias((String) newValue);

			} else if (propertyName.equals(Item.WHERE)) {
				item.setWhere((String) newValue);

			} else if (propertyName.equals(Item.GROUP_BY)) {
				item.setGroupBy((SQLGroupFunction) converter
								.convertToComplexType(newValue,
										SQLGroupFunction.class));

			} else if (propertyName.equals(Item.HAVING)) {
				item.setHaving((String) newValue);

			} else if (propertyName.equals(Item.ORDER_BY)) {
				item.setOrderBy((OrderByArgument) converter
						.convertToComplexType(newValue, OrderByArgument.class));

			} else if (propertyName.equals(Item.SELECTED)) {
				item.setSelected((Integer) newValue);

			} else if (propertyName.equals(Item.WHERE)) {
				item.setWhere((String) newValue);

			} else if (propertyName.equals("orderByOrdering")) {
				item.setOrderByOrdering((Integer) newValue);

			} else if (propertyName.equals("colWidth")) {
				item.setColumnWidth((Integer) newValue);

			} else {
				throw new WabitPersistenceException(uuid,
						getWabitPersistenceExceptionMessage(wabitItem, propertyName));
			}
		} else {
			throw new WabitPersistenceException(uuid, "Unknown WabitItem with name "
					+ wabitItem.getName());
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitJoin} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param wabitJoin
	 *            The {@link WabitJoin} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitJoinProperty(WabitJoin wabitJoin, String propertyName)
			throws WabitPersistenceException {

		if (propertyName.equals("leftColumnOuterJoin")) {
			return wabitJoin.isLeftColumnOuterJoin();
		} else if (propertyName.equals("rightColumnOuterJoin")) {
			return wabitJoin.isRightColumnOuterJoin();
		} else if (propertyName.equals("comparator")) {
			return wabitJoin.getComparator();
		} else {
			throw new WabitPersistenceException(wabitJoin.getUUID(),
					getWabitPersistenceExceptionMessage(wabitJoin, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitJoin} object property.
	 * 
	 * @param wabitJoin
	 *            The {@link WabitJoin} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The property value
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitJoinProperty(WabitJoin wabitJoin,
			String propertyName, Object newValue)
			throws WabitPersistenceException {

		if (propertyName.equals("leftColumnOuterJoin")) {
			wabitJoin.setLeftColumnOuterJoin((Boolean) newValue);
		} else if (propertyName.equals("rightColumnOuterJoin")) {
			wabitJoin.setRightColumnOuterJoin((Boolean) newValue);
		} else if (propertyName.equals("comparator")) {
			wabitJoin.setComparator((String) newValue);
		} else {
			throw new WabitPersistenceException(wabitJoin.getUUID(),
					getWabitPersistenceExceptionMessage(wabitJoin, propertyName));
		}
	}

	/**
	 * Retrieves a property value from an {@link OlapQuery} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param olapQuery
	 *            The {@link OlapQuery} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getOlapQueryProperty(OlapQuery olapQuery, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("olapDataSource")) {
			return converter.convertToBasicType(olapQuery.getOlapDataSource(),
					DataType.OLAP4J_DATA_SOURCE);

		} else if (propertyName.equals("catalogName")) {
			return olapQuery.getCatalogName();

		} else if (propertyName.equals("schemaName")) {
			return olapQuery.getSchemaName();

		} else if (propertyName.equals("cubeName")) {
			return olapQuery.getCubeName();

		} else if (propertyName.equals("currentCube")) {
			return converter.convertToBasicType(olapQuery.getCurrentCube(),
					DataType.CUBE, olapQuery.getOlapDataSource());

		} else if (propertyName.equals("nonEmpty")) {
			return olapQuery.isNonEmpty();

		} else {
			throw new WabitPersistenceException(olapQuery.getUUID(),
					getWabitPersistenceExceptionMessage(olapQuery, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link OlapQuery} object property
	 * 
	 * @param olapQuery
	 *            The {@link OlapQuery} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method
	 *             or if the new value could not be committed.
	 */
	private void commitOlapQueryProperty(OlapQuery olapQuery,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("olapDataSource")) {
			olapQuery.setOlapDataSource((Olap4jDataSource) converter
					.convertToComplexType(newValue, Olap4jDataSource.class));

		} else if (propertyName.equals("currentCube")) {
			try {
				olapQuery.setCurrentCube((Cube) converter.convertToComplexType(
						newValue, Cube.class));
			} catch (SQLException e) {
				throw new WabitPersistenceException(olapQuery.getUUID(), 
						"Cannot commit currentCube property for OlapQuery with name \"" 
						+ olapQuery.getName() + "\" and UUID \"" + olapQuery.getUUID() 
						+ "\" to value " + newValue.toString(), e);
			}

		} else if (propertyName.equals("nonEmpty")) {
			olapQuery.setNonEmpty((Boolean) newValue);
		} else {
			throw new WabitPersistenceException(olapQuery.getUUID(),
					getWabitPersistenceExceptionMessage(olapQuery, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitOlapSelection} object based
	 * on the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param selection
	 *            The {@link WabitOlapSelection} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitOlapSelectionProperty(WabitOlapSelection selection,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("operator")) {
			return converter.convertToBasicType(selection.getOperator(),
					DataType.ENUM);

		} else if (propertyName.equals("uniqueMemberName")) {
			return selection.getUniqueMemberName();

		} else {
			throw new WabitPersistenceException(selection.getUUID(),
					getWabitPersistenceExceptionMessage(selection, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link WabitOlapSelection} object property.
	 * Currently, uncommon properties cannot be set.
	 * 
	 * @param selection
	 *            The {@link WabitOlapSelection} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitOlapSelectionProperty(WabitOlapSelection selection,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		throw new WabitPersistenceException(selection.getUUID(),
				getWabitPersistenceExceptionMessage(selection, propertyName));
	}

	/**
	 * Retrieve a property value from a WabitOlapDimension object based on the
	 * property name and converts it to something that can be passed to a
	 * persister. Currently, there are no uncommon properties to retrieve.
	 * 
	 * @param dimension
	 *            The {@link WabitOlapDimension} to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitOlapDimensionProperty(WabitOlapDimension dimension,
			String propertyName) throws WabitPersistenceException {
		throw new WabitPersistenceException(dimension.getUUID(),
				getWabitPersistenceExceptionMessage(dimension, propertyName));
	}

	/**
	 * Commits a persisted {@link WabitOlapDimension} object property
	 * 
	 * @param dimension
	 *            The {@link WabitOlapDimension} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitOlapDimensionProperty(WabitOlapDimension dimension,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		throw new WabitPersistenceException(dimension.getUUID(),
				getWabitPersistenceExceptionMessage(dimension, propertyName));
	}

	/**
	 * Retrieves a property value from a {@link WabitOlapAxis} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param olapAxis
	 *            The {@link WabitOlapAxis} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitOlapAxisProperty(WabitOlapAxis olapAxis,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("ordinal")) {
			return olapAxis.getOrdinal().axisOrdinal();

		} else if (propertyName.equals("nonEmpty")) {
			return olapAxis.isNonEmpty();

		} else if (propertyName.equals("sortEvaluationLiteral")) {
			return olapAxis.getSortEvaluationLiteral();

		} else if (propertyName.equals("sortOrder")) {
			return olapAxis.getSortOrder();

		} else {
			throw new WabitPersistenceException(olapAxis.getUUID(),
					getWabitPersistenceExceptionMessage(olapAxis, propertyName));
		}

	}

	/**
	 * Commits a persisted {@link WabitOlapAxis} object property
	 * 
	 * @param olapAxis
	 *            The {@link WabitOlapAxis} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitWabitOlapAxisProperty(WabitOlapAxis olapAxis,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("nonEmpty")) {
			olapAxis.setNonEmpty((Boolean) newValue);

		} else if (propertyName.equals("sortEvaluationLiteral")) {
			olapAxis.setSortEvaluationLiteral((String) newValue);

		} else if (propertyName.equals("sortOrder")) {
			olapAxis.setSortOrder((String) newValue);

		} else {
			throw new WabitPersistenceException(olapAxis.getUUID(),
					getWabitPersistenceExceptionMessage(olapAxis, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link Chart} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param chart
	 *            The {@link Chart} object to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getChartProperty(Chart chart, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("xaxisName")) {
			return chart.getXaxisName();

		} else if (propertyName.equals("yaxisName")) {
			return chart.getYaxisName();

		} else if (propertyName.equals("XAxisLabelRotation")) {
			return chart.getXAxisLabelRotation();

		} else if (propertyName.equals("gratuitousAnimated")) {
			return chart.isGratuitouslyAnimated();

		} else if (propertyName.equals("type")) {
			return chart.getType().toString();

		} else if (propertyName.equals("legendPosition")) {
			return converter.convertToBasicType(chart.getLegendPosition(),
					DataType.ENUM);

		} else if (propertyName.equals("query")) {
			return chart.getQuery().getUUID();

		} else {
			throw new WabitPersistenceException(chart.getUUID(),
					getWabitPersistenceExceptionMessage(chart, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link Chart} object property
	 * 
	 * @param chart
	 *            The {@link Chart} object to commit the persisted property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitChartProperty(Chart chart, String propertyName,
			Object newValue) throws WabitPersistenceException {
		String uuid = chart.getUUID();

		if (propertyName.equals("xaxisName")) {
			chart.setXaxisName((String) newValue);

		} else if (propertyName.equals("yaxisName")) {
			chart.setYaxisName((String) newValue);

		} else if (propertyName.equals("xAxisLabelRrotation")) {
			chart.setXAxisLabelRotation((Double) newValue);

		} else if (propertyName.equals("gratuitousAnimated")) {
			chart.setGratuitouslyAnimated((Boolean) newValue);

		} else if (propertyName.equals("type")) {
			chart.setType((ChartType) converter.convertToComplexType(newValue,
					ChartType.class));

		} else if (propertyName.equals("legendPosition")) {
			chart.setLegendPosition((LegendPosition) converter
					.convertToComplexType(newValue, LegendPosition.class));

		} else if (propertyName.equals("query")) {
			ResultSetProducer rsProducer = (ResultSetProducer) converter
					.convertToComplexType(newValue, ResultSetProducer.class);
			if (rsProducer == null) {
				throw new WabitPersistenceException(uuid,
						getNotDefinedPropertyExceptionMessage(chart, propertyName, newValue));
			}

			try {
				chart.setQuery(rsProducer);
			} catch (SQLException e) {
				throw new WabitPersistenceException(uuid, 
						"Cannot commit property query on Chart with name \"" 
						+ chart.getName() + "\" and UUID \"" + chart.getUUID() 
						+ "\" for value \"" + newValue.toString() + "\"", e);
			}

		} else {
			throw new WabitPersistenceException(uuid,
					getWabitPersistenceExceptionMessage(chart, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link ChartColumn} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param chartColumn
	 *            The {@link ChartColumn} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getChartColumnProperty(ChartColumn chartColumn,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("roleInChart")) {
			return converter.convertToBasicType(chartColumn.getRoleInChart(),
					DataType.ENUM);

		} else if (propertyName.equals("XAxisIdentifier")) {
			return converter.convertToBasicType(chartColumn.getXAxisIdentifier(), 
					DataType.REFERENCE);

		} else {
			throw new WabitPersistenceException(chartColumn.getUUID(),
					getWabitPersistenceExceptionMessage(chartColumn, propertyName));
		}

	}

	/**
	 * Commits a persisted {@link ChartColumn} object property
	 * 
	 * @param chartColumn
	 *            The {@link ChartColumn} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitChartColumnProperty(ChartColumn chartColumn,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("roleInChart")) {
			chartColumn.setRoleInChart((ColumnRole) converter
					.convertToComplexType(newValue, ColumnRole.class));

		} else if (propertyName.equals("XAxisIdentifier")) {
			chartColumn.setXAxisIdentifier((ChartColumn) converter.convertToComplexType(
					newValue, ChartColumn.class));

		} else {
			throw new WabitPersistenceException(chartColumn.getUUID(),
					getWabitPersistenceExceptionMessage(chartColumn, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link WabitWorkspace} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param wabitImage
	 *            The {@link WabitImage} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getWabitImageProperty(WabitImage wabitImage,
			String propertyName) throws WabitPersistenceException {
		String uuid = wabitImage.getUUID();

		if (propertyName.equals("image")) {
			final Image wabitInnerImage = wabitImage.getImage();
			return PersisterUtils.convertImageToStreamAsPNG(wabitInnerImage).toByteArray();

		} else {
			throw new WabitPersistenceException(uuid,
					getWabitPersistenceExceptionMessage(wabitImage, propertyName));
		}

	}

	/**
	 * Commits a persisted {@link WabitImage} object property
	 * 
	 * @param wabitImage
	 *            The {@link WabitImage} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method
	 *             or if the new value could not be commited.
	 */
	private void commitWabitImageProperty(WabitImage wabitImage,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		String uuid = wabitImage.getUUID();

		if (propertyName.equals("image")) {
			try {
				wabitImage.setImage(ImageIO.read((InputStream) newValue));
			} catch (IOException e) {
				throw new WabitPersistenceException(uuid,
						"Cannot commit WabitImage as the input stream could not be read.", e);
			}

		} else {
			throw new WabitPersistenceException(uuid,
					getWabitPersistenceExceptionMessage(wabitImage, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link Layout} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param layout
	 *            The {@link Layout} object to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getLayoutProperty(Layout layout, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("zoomLevel")) {
			return layout.getZoomLevel();

		} else {
			throw new WabitPersistenceException(layout.getUUID(),
					getWabitPersistenceExceptionMessage(layout, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link Layout} object property
	 * 
	 * @param layout
	 *            The {@link Layout} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitLayoutProperty(Layout layout, String propertyName,
			Object newValue) throws WabitPersistenceException {
		if (propertyName.equals("zoomLevel")) {
			layout.setZoomLevel((Integer) newValue);

		} else {
			throw new WabitPersistenceException(layout.getUUID(),
					getWabitPersistenceExceptionMessage(layout, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link Page} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param page
	 *            The {@link Page} object to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getPageProperty(Page page, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("height")) {
			return page.getHeight();

		} else if (propertyName.equals("width")) {
			return page.getWidth();

		} else if (propertyName.equals("orientation")) {
			return converter.convertToBasicType(page.getOrientation(),
					DataType.ENUM);

		} else if (propertyName.equals("defaultFont")) {
			return converter.convertToBasicType(page.getDefaultFont(),
					DataType.FONT);

		} else {
			throw new WabitPersistenceException(page.getUUID(),
					getWabitPersistenceExceptionMessage(page, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link Page} object property
	 * 
	 * @param page
	 *            The {@link Page} object to commit the persisted property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitPageProperty(Page page, String propertyName,
			Object newValue) throws WabitPersistenceException {
		if (propertyName.equals("height")) {
			page.setHeight((Integer) newValue);

		} else if (propertyName.equals("width")) {
			page.setWidth((Integer) newValue);

		} else if (propertyName.equals("orientation")) {
			page.setOrientation((PageOrientation) converter
					.convertToComplexType(newValue, PageOrientation.class));

		} else if (propertyName.equals("defaultFont")) {
			page.setDefaultFont((Font) converter.convertToComplexType(newValue,
					Font.class));

		} else {
			throw new WabitPersistenceException(page.getUUID(),
					getWabitPersistenceExceptionMessage(page, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link ContentBox} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param contentBox
	 *            The {@link ContentBox} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getContentBoxProperty(ContentBox contentBox,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("height")) {
			return contentBox.getHeight();

		} else if (propertyName.equals("width")) {
			return contentBox.getWidth();

		} else if (propertyName.equals("x")) {
			return contentBox.getX();

		} else if (propertyName.equals("y")) {
			return contentBox.getY();

		} else if (propertyName.equals("contentRenderer")) {
			return converter.convertToBasicType(
					contentBox.getContentRenderer(), DataType.REFERENCE);

		} else if (propertyName.equals("font")) {
			return converter.convertToBasicType(contentBox.getFont(),
					DataType.FONT);

		} else {
			throw new WabitPersistenceException(contentBox.getUUID(),
					getWabitPersistenceExceptionMessage(contentBox, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link ContentBox} object property
	 * 
	 * @param contentBox
	 *            The {@link ContentBox} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitContentBoxProperty(ContentBox contentBox,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("height")) {
			contentBox.setHeight((Double) newValue);

		} else if (propertyName.equals("width")) {
			contentBox.setWidth((Double) newValue);

		} else if (propertyName.equals("x")) {
			contentBox.setX((Double) newValue);

		} else if (propertyName.equals("y")) {
			contentBox.setY((Double) newValue);

		} else if (propertyName.equals("contentRenderer")) {
			contentBox.setContentRenderer((ReportContentRenderer)
					converter.convertToComplexType(newValue,
							ReportContentRenderer.class));

		} else if (propertyName.equals("font")) {
			contentBox.setFont((Font) converter.convertToComplexType(newValue,
					Font.class));

		} else {
			throw new WabitPersistenceException(contentBox.getUUID(),
					getWabitPersistenceExceptionMessage(contentBox, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link ChartRenderer} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister. Currently, uncommon properties cannot be retrieved from this
	 * object.
	 * 
	 * @param cRenderer
	 *            The {@link ChartRenderer} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getChartRendererProperty(ChartRenderer cRenderer,
			String propertyName) throws WabitPersistenceException {
		throw new WabitPersistenceException(cRenderer.getUUID(),
				getWabitPersistenceExceptionMessage(cRenderer, propertyName));
	}

	/**
	 * Commits a persisted {@link ChartRenderer} object property
	 * 
	 * @param cRenderer
	 *            The {@link ChartRenderer} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitChartRendererProperty(ChartRenderer cRenderer,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		throw new WabitPersistenceException(cRenderer.getUUID(),
				getWabitPersistenceExceptionMessage(cRenderer, propertyName));
	}

	/**
	 * Retrieves a property value from a {@link CellSetRenderer} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param csRenderer
	 *            The {@link CellSetRenderer} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getCellSetRendererProperty(CellSetRenderer csRenderer,
			String propertyName) throws WabitPersistenceException {

		if (propertyName.equals("bodyAlignment")) {
			return converter.convertToBasicType(csRenderer.getBodyAlignment(),
					DataType.ENUM);

		} else if (propertyName.equals("bodyFormat")) {
			return converter.convertToBasicType(csRenderer.getBodyFormat(),
					DataType.DECIMAL_FORMAT);

		} else if (propertyName.equals("headerFont")) {
			return converter.convertToBasicType(csRenderer.getHeaderFont(),
					DataType.FONT);

		} else if (propertyName.equals("bodyFont")) {
			return converter.convertToBasicType(csRenderer.getBodyFont(),
					DataType.FONT);

		} else {
			throw new WabitPersistenceException(csRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(csRenderer, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link CellSetRenderer} object property
	 * 
	 * @param csRenderer
	 *            The {@link CellSetRenderer} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitCellSetRendererProperty(CellSetRenderer csRenderer,
			String propertyName, Object newValue)
			throws WabitPersistenceException {

		if (propertyName.equals("bodyAlignment")) {
			csRenderer.setBodyAlignment((HorizontalAlignment) converter
					.convertToComplexType(newValue, HorizontalAlignment.class));

		} else if (propertyName.equals("bodyFormat")) {
			csRenderer.setBodyFormat((DecimalFormat) converter
					.convertToComplexType(newValue, DecimalFormat.class));

		} else if (propertyName.equals("headerFont")) {
			csRenderer.setHeaderFont((Font) converter.convertToComplexType(
					newValue, Font.class));

		} else if (propertyName.equals("bodyFont")) {
			csRenderer.setBodyFont((Font) converter.convertToComplexType(
					newValue, Font.class));

		} else {
			throw new WabitPersistenceException(csRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(csRenderer, propertyName));
		}
	}

	/**
	 * Retrieves a property value from an {@link ImageRenderer} object based on
	 * the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param iRenderer
	 *            The {@link ImageRenderer} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getImageRendererProperty(ImageRenderer iRenderer,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("image")) {
			return converter.convertToBasicType(iRenderer.getImage(),
					DataType.REFERENCE);

		} else if (propertyName.equals("preservingAspectRatio")) {
			return iRenderer.isPreservingAspectRatio();

		} else if (propertyName.equals("preserveAspectRatioWhenResizing")) {
			return iRenderer.isPreserveAspectRatioWhenResizing();

		} else if (propertyName.equals("HAlign")) {
			return converter.convertToBasicType(iRenderer.getHAlign(),
					DataType.ENUM);

		} else if (propertyName.equals("VAlign")) {
			return converter.convertToBasicType(iRenderer.getVAlign(),
					DataType.ENUM);

		} else {
			throw new WabitPersistenceException(iRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(iRenderer, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link ImageRenderer} object property
	 * 
	 * @param iRenderer
	 *            The {@link ImageRenderer} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 */
	private void commitImageRendererProperty(ImageRenderer iRenderer,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("image")) {
			iRenderer.setImage((WabitImage) converter.convertToComplexType(
					newValue, WabitImage.class));

		} else if (propertyName.equals("preservingAspectRatio")) {
			iRenderer.setPreservingAspectRatio((Boolean) newValue);

		} else if (propertyName.equals("preserveAspectRatioWhenResizing")) {
			iRenderer.setPreserveAspectRatioWhenResizing((Boolean) newValue);

		} else if (propertyName.equals("HAlign")) {
			iRenderer.setHAlign((HorizontalAlignment) converter
					.convertToComplexType(newValue, HorizontalAlignment.class));

		} else if (propertyName.equals("VAlign")) {
			iRenderer.setVAlign((VerticalAlignment) converter
					.convertToComplexType(newValue, VerticalAlignment.class));

		} else {
			throw new WabitPersistenceException(iRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(iRenderer, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link Label} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param label
	 *            The {@link Label} object to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getLabelProperty(Label label, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("horizontalAlignment")) {
			return converter.convertToBasicType(label.getHorizontalAlignment(),
					DataType.ENUM);

		} else if (propertyName.equals("verticalAlignment")) {
			return converter.convertToBasicType(label.getVerticalAlignment(),
					DataType.ENUM);

		} else if (propertyName.equals("text")) {
			return label.getText();

		} else if (propertyName.equals("backgroundColour")) {
			return converter.convertToBasicType(label.getBackgroundColour(),
					DataType.COLOR);

		} else if (propertyName.equals("font")) {
			return converter.convertToBasicType(label.getFont(), DataType.FONT);

		} else {
			throw new WabitPersistenceException(label.getUUID(),
					getWabitPersistenceExceptionMessage(label, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link Label} object property
	 * 
	 * @param label
	 *            The {@link Label} object to commit the persisted property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 */
	private void commitLabelProperty(Label label, String propertyName,
			Object newValue) throws WabitPersistenceException {
		if (propertyName.equals("horizontalAlignment")) {
			label.setHorizontalAlignment((HorizontalAlignment) converter
					.convertToComplexType(newValue, HorizontalAlignment.class));

		} else if (propertyName.equals("verticalAlignment")) {
			label.setVerticalAlignment((VerticalAlignment) converter
					.convertToComplexType(newValue, VerticalAlignment.class));

		} else if (propertyName.equals("text")) {
			label.setText((String) newValue);

		} else if (propertyName.equals("backgroundColour")) {
			label.setBackgroundColour((Color) converter.convertToComplexType(
					newValue, Color.class));

		} else if (propertyName.equals("font")) {
			label.setFont((Font) converter.convertToComplexType(newValue,
					Font.class));

		} else {
			throw new WabitPersistenceException(label.getUUID(),
					getWabitPersistenceExceptionMessage(label, propertyName));
		}
	}

	private Object getReportTaskProperty(ReportTask task, String propertyName)
			throws WabitPersistenceException {

		if (propertyName.equals("email")) {
			return converter.convertToBasicType(task.getEmail(),
					DataType.STRING);
		} else if (propertyName.equals("report")) {
			return converter.convertToBasicType(task.getReport(),
					DataType.REFERENCE);
		} else if (propertyName.equals("triggerType")) {
			return converter.convertToBasicType(task.getTriggerType(),
					DataType.STRING);
		} else if (propertyName.equals("triggerHourParam")) {
			return converter.convertToBasicType(task.getTriggerHourParam(),
					DataType.INTEGER);
		} else if (propertyName.equals("triggerMinuteParam")) {
			return converter.convertToBasicType(task.getTriggerMinuteParam(),
					DataType.INTEGER);
		} else if (propertyName.equals("triggerDayOfWeekParam")) {
			return converter.convertToBasicType(
					task.getTriggerDayOfWeekParam(), DataType.INTEGER);
		} else if (propertyName.equals("triggerDayOfMonthParam")) {
			return converter.convertToBasicType(task
					.getTriggerDayOfMonthParam(), DataType.INTEGER);
		} else if (propertyName.equals("triggerIntervalParam")) {
			return converter.convertToBasicType(task.getTriggerIntervalParam(),
					DataType.INTEGER);

		} else {
			throw new WabitPersistenceException(task.getUUID(),
					"Unknown property " + propertyName + " for ReportTask with name " 
					+ task.getName());
		}
	}

	/**
	 * Retrieves a property value from a {@link ResultSetRenderer} object based
	 * on the property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param rsRenderer
	 *            The {@link ResultSetRenderer} object to retrieve the named
	 *            property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getResultSetRendererProperty(ResultSetRenderer rsRenderer,
			String propertyName) throws WabitPersistenceException {
		if (propertyName.equals("nullString")) {
			return rsRenderer.getNullString();

		} else if (propertyName.equals("borderType")) {
			return converter.convertToBasicType(rsRenderer.getBorderType(),
					DataType.ENUM);

		} else if (propertyName.equals("backgroundColour")) {
			return converter.convertToBasicType(rsRenderer
					.getBackgroundColour(), DataType.COLOR);

		} else if (propertyName.equals("headerFont")) {
			return converter.convertToBasicType(rsRenderer.getHeaderFont(),
					DataType.FONT);

		} else if (propertyName.equals("bodyFont")) {
			return converter.convertToBasicType(rsRenderer.getBodyFont(),
					DataType.FONT);

		} else {
			throw new WabitPersistenceException(rsRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(rsRenderer, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link ResultSetRenderer} object property
	 * 
	 * @param rsRenderer
	 *            The {@link ResultSetRenderer} object to commit the persisted
	 *            property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitResultSetRendererProperty(ResultSetRenderer rsRenderer,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		if (propertyName.equals("nullString")) {
			rsRenderer.setNullString((String) newValue);

		} else if (propertyName.equals("border")) {
			rsRenderer.setBorderType((BorderStyles) converter
					.convertToComplexType(newValue, BorderStyles.class));

		} else if (propertyName.equals("backgroundColour")) {
			rsRenderer.setBackgroundColour((Color) converter
					.convertToComplexType(newValue, Color.class));

		} else if (propertyName.equals("headerFont")) {
			rsRenderer.setHeaderFont((Font) converter.convertToComplexType(
					newValue, Font.class));

		} else if (propertyName.equals("bodyFont")) {
			rsRenderer.setBodyFont((Font) converter.convertToComplexType(
					newValue, Font.class));

		} else {
			throw new WabitPersistenceException(rsRenderer.getUUID(),
					getWabitPersistenceExceptionMessage(rsRenderer, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link ColumnInfo} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param colInfo
	 *            The {@link ColumnInfo} object to retrieve the named property
	 *            from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getColumnInfoProperty(ColumnInfo colInfo, String propertyName)
			throws WabitPersistenceException {
		String uuid = colInfo.getUUID();

		if (propertyName.equals(ColumnInfo.COLUMN_ALIAS)) {
			return colInfo.getColumnAlias();

		} else if (propertyName.equals(ColumnInfo.WIDTH_CHANGED)) {
			return colInfo.getWidth();

		} else if (propertyName.equals(ColumnInfo.HORIZONAL_ALIGNMENT_CHANGED)) {
			return colInfo.getHorizontalAlignment().name();

		} else if (propertyName.equals(ColumnInfo.DATATYPE_CHANGED)) {
			return colInfo.getDataType().name();

		} else if (propertyName.equals(ColumnInfo.WILL_GROUP_OR_BREAK_CHANGED)) {
			return colInfo.getWillGroupOrBreak().name();

		} else if (propertyName.equals(ColumnInfo.WILL_SUBTOTAL_CHANGED)) {
			return colInfo.getWillSubtotal();

		} else if (propertyName.equals(ColumnInfo.COLUMN_INFO_ITEM_CHANGED)) {
			return colInfo.getColumnInfoItem().getUUID();

		} else if (propertyName.equals(ColumnInfo.FORMAT_CHANGED)) {
			Format formatType = colInfo.getFormat();

			if (formatType instanceof SimpleDateFormat) {
				return "date-format";
			} else if (formatType instanceof DecimalFormat) {
				return "decimal-format";
			} else {
				throw new WabitPersistenceException(uuid,
						"Invalid format-type: " + formatType.toString());
			}

		} else {
			throw new WabitPersistenceException(uuid,
					getWabitPersistenceExceptionMessage(colInfo, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link ColumnInfo} object property
	 * 
	 * @param colInfo
	 *            The {@link ColumnInfo} object to commit the persisted property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method or if
	 *             the new property value cannot be committed.
	 */
	private void commitColumnInfoProperty(ColumnInfo colInfo,
			String propertyName, Object newValue)
			throws WabitPersistenceException {
		String uuid = colInfo.getUUID();
		if (propertyName.equals(ColumnInfo.COLUMN_ALIAS)) {
			colInfo.setColumnAlias((String) newValue);

		} else if (propertyName.equals(ColumnInfo.WIDTH_CHANGED)) {
			colInfo.setWidth((Integer) newValue);

		} else if (propertyName.equals(ColumnInfo.HORIZONAL_ALIGNMENT_CHANGED)) {
			colInfo.setHorizontalAlignment((HorizontalAlignment) converter
					.convertToComplexType(newValue, HorizontalAlignment.class));

		} else if (propertyName.equals(ColumnInfo.DATATYPE_CHANGED)) {
			colInfo.setDataType((ca.sqlpower.wabit.report.DataType)
					converter.convertToComplexType(
									newValue,
									ca.sqlpower.wabit.report.chart.ChartColumn.DataType.class));

		} else if (propertyName.equals(ColumnInfo.WILL_GROUP_OR_BREAK_CHANGED)) {
			colInfo.setWillGroupOrBreak((GroupAndBreak) converter
					.convertToComplexType(newValue, GroupAndBreak.class));

		} else if (propertyName.equals(ColumnInfo.WILL_SUBTOTAL_CHANGED)) {
			colInfo.setWillSubtotal((Boolean) newValue);

		} else if (propertyName.equals(ColumnInfo.COLUMN_INFO_ITEM_CHANGED)) {
			boolean found = false;
			for (Item queryItem : ((ResultSetRenderer) colInfo.getParent())
					.getContent().getSelectedColumns()) {
				Item item = queryItem;
				if (item.getUUID().equals((String) newValue)) {
					colInfo.setColumnInfoItem(item);
					found = true;
					break;
				}
			}
			if (!found) {
				throw new WabitPersistenceException(uuid,
						"Could not find QueryItem with uuid "
								+ newValue.toString() + " for property " + propertyName
								+ " for parent " + colInfo.getName());
			}

		} else if (propertyName.equals(ColumnInfo.FORMAT_CHANGED)) {
			Format formatType = colInfo.getFormat();
			String newFormatType = newValue.toString();
			String pattern = "";

			if (formatType instanceof SimpleDateFormat) {
				pattern = ((SimpleDateFormat) formatType).toPattern();
			} else if (formatType instanceof DecimalFormat) {
				pattern = ((DecimalFormat) formatType).toPattern();
			} else {
				throw new WabitPersistenceException(uuid,
						"Invalid format-type: " + formatType.toString());
			}

			if (newFormatType.equals("date-format")) {
				colInfo.setFormat(new SimpleDateFormat(pattern));
			} else if (newFormatType.equals("decimal-format")) {
				colInfo.setFormat(new DecimalFormat(pattern));
			} else {
				throw new WabitPersistenceException(uuid,
						"Invalid format-type: " + formatType.toString());
			}

		} else {
			throw new WabitPersistenceException(uuid,
					getWabitPersistenceExceptionMessage(colInfo, propertyName));
		}
	}

	/**
	 * Retrieves a property value from a {@link Guide} object based on the
	 * property name and converts it to something that can be passed to a
	 * persister.
	 * 
	 * @param guide
	 *            The {@link Guide} object to retrieve the named property from.
	 * @param propertyName
	 *            The property name that needs to be retrieved and converted.
	 *            This is the name of the property in the class itself based on
	 *            the property fired by the setter for the event which is
	 *            enforced by tests using JavaBeans methods even though the
	 *            values are hard coded in here and won't change if the class
	 *            changes.
	 * @return The value stored in the variable of the object we are given at
	 *         the property name after it has been converted to a type that can
	 *         be stored. The conversion is based on the
	 *         {@link SessionPersisterSuperConverter}.
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private Object getGuideProperty(Guide guide, String propertyName)
			throws WabitPersistenceException {
		if (propertyName.equals("offset")) {
			return guide.getOffset();

		} else {
			throw new WabitPersistenceException(guide.getUUID(),
					getWabitPersistenceExceptionMessage(guide, propertyName));
		}
	}

	/**
	 * Commits a persisted {@link Guide} object property
	 * 
	 * @param guide
	 *            The {@link Guide} object to commit the persisted property upon
	 * @param propertyName
	 *            The property name
	 * @param newValue
	 *            The persisted property value to be committed
	 * @throws WabitPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void commitGuideProperty(Guide guide, String propertyName,
			Object newValue) throws WabitPersistenceException {
		if (propertyName.equals("offset")) {
			guide.setOffset((Double) newValue);

		} else {
			throw new WabitPersistenceException(guide.getUUID(),
					getWabitPersistenceExceptionMessage(guide, propertyName));
		}
	}

	/**
	 * Removes {@link WabitObject}s from persistent storage.
	 * 
	 * @param parentUUID
	 *            The parent UUID of the {@link WabitObject} to remove
	 * @param uuid
	 *            The UUID of the {@link WabitObject} to remove
	 * @throws WabitPersistenceException
	 */
	public void removeObject(String parentUUID, String uuid)
			throws WabitPersistenceException {

		if (!exists(uuid)) {
			throw new WabitPersistenceException(uuid,
					"Cannot remove the WabitObject with UUID " + uuid 
					+ " from parent UUID " + parentUUID + " as it does not exist.");
		}

		objectsToRemove.put(uuid, parentUUID);

		if (transactionCount == 0) {
			commitRemovals();
		}
	}

	/**
	 * Rollback all changes to persistent storage to the beginning of the
	 * transaction
	 * 
	 * @throws WabitPersistenceException
	 */
	public void rollback() throws WabitPersistenceException {
		// TODO Auto-generated method stub
		if (transactionCount <= 0) {
			throw new WabitPersistenceException(null,
					"Cannot rollback while not in a transaction.");
		}
	}

	/**
	 * Accessor for the {@link WabitSession} object.
	 * 
	 * @return The {@link WabitSession} object this class refers to
	 */
	public WabitSession getWabitSession() {
		return session;
	}

	/**
	 * Mutator for the {@link WabitSession} object.
	 * 
	 * @param session
	 *            The {@link WabitSession} object to make this class refer to
	 */
	public void setWabitSession(WabitSession session) {
		this.session = session;
	}

}
