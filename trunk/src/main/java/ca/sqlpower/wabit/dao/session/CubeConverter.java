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

package ca.sqlpower.wabit.dao.session;

import org.apache.commons.beanutils.ConversionException;
import org.olap4j.OlapException;
import org.olap4j.metadata.Catalog;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Schema;

import ca.sqlpower.sql.DataSourceCollection;
import ca.sqlpower.sql.Olap4jDataSource;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.wabit.OlapConnectionMapping;

public class CubeConverter implements BidirectionalConverter<String, Cube> {

	/**
	 * This delimiter will separate the unique identifiers that define a cube.
	 * This value should not be a valid character or set of characters in any of
	 * the unique identifiers that define a cube.
	 */
	private static final String DELIMITER = ",";

	/**
	 * This is the connection map that contains the connection that contains the cube we are converting from a
	 * string. If we are only converting cubes to strings then this value will
	 * be unused.
	 */
	private final OlapConnectionMapping mapping;

	private final DataSourceCollection<SPDataSource> dsCollection;

	public CubeConverter(OlapConnectionMapping con, DataSourceCollection<SPDataSource> dsCollection) {
		this.mapping = con;
		this.dsCollection = dsCollection;
	}

	public Cube convertToComplexType(String convertFrom) throws ConversionException {

		String[] cubePieces = convertFrom.split(DELIMITER);
		if (cubePieces.length != 4) {
			throw new IllegalArgumentException("The cube representation \"" + convertFrom + 
					"\" has an incorrect number of pieces.");
		}
		Olap4jDataSource ds = dsCollection.getDataSource(cubePieces[0], Olap4jDataSource.class);
		
		Catalog catalog;
		try {
			catalog = mapping.createConnection(ds).getCatalogs().get(cubePieces[1]);
		} catch (Exception ex) {
			throw new ConversionException("Error connecting to data source " + cubePieces[0] + 
					" to get cube defined by " + convertFrom, ex);
		}
		Schema schema;
		try {
			schema = catalog.getSchemas().get(cubePieces[2]);
			Cube cube = schema.getCubes().get(cubePieces[3]);
			return cube;
		} catch (OlapException e) {
			throw new ConversionException("The cube could not be retrieved from the string " + 
					convertFrom, e);
		}
	}

	/**
	 * The additional information that must be provided for this method is the data source
	 * that the cube is contained in.
	 */
	public String convertToSimpleType(Cube convertFrom, Object ... additionalInfo) {
		SPDataSource ds = (SPDataSource) additionalInfo[0];
		String name = convertFrom.getName();
		String schemaName = convertFrom.getSchema().getName();
		Catalog catalog = convertFrom.getSchema().getCatalog();
		String catalogName = catalog.getName();

		StringBuilder result = new StringBuilder();
		result.append(ds.getName());
		result.append(DELIMITER);
		result.append(catalogName);
		result.append(DELIMITER);
		result.append(schemaName);
		result.append(DELIMITER);
		result.append(name);

		return result.toString();
	}

}
