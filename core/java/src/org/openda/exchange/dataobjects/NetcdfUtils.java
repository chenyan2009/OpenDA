/* MOD_V2.0 
* Copyright (c) 2012 OpenDA Association
* All rights reserved.
* 
* This file is part of OpenDA. 
* 
* OpenDA is free software: you can redistribute it and/or modify 
* it under the terms of the GNU Lesser General Public License as 
* published by the Free Software Foundation, either version 3 of 
* the License, or (at your option) any later version. 
* 
* OpenDA is distributed in the hope that it will be useful, 
* but WITHOUT ANY WARRANTY; without even the implied warranty of 
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
* GNU Lesser General Public License for more details. 
* 
* You should have received a copy of the GNU Lesser General Public License
* along with OpenDA.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.openda.exchange.dataobjects;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.openda.exchange.ArrayGeometryInfo;
import org.openda.exchange.IrregularGridGeometryInfo;
import org.openda.exchange.QuantityInfo;
import org.openda.exchange.TimeInfo;
import org.openda.exchange.dataobjects.NetcdfDataObject.GridStartCorner;
import org.openda.interfaces.IArray;
import org.openda.interfaces.IArrayGeometryInfo;
import org.openda.interfaces.IArrayTimeInfo;
import org.openda.interfaces.IExchangeItem;
import org.openda.interfaces.IGeometryInfo;
import org.openda.interfaces.IQuantityInfo;
import org.openda.interfaces.ITimeInfo;
import org.openda.utils.Array;
import org.openda.utils.Time;

import ucar.ma2.ArrayDouble;
import ucar.ma2.ArrayObject;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NCdumpW;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileWriteable;
import ucar.nc2.Variable;
import ucar.nc2.units.DateUnit;

/**
 * This class contains utility methods for reading/writing data from/to netcdf files.
 * The methods in this class conform as much as possible to version 1.5 of the CF-conventions
 * (NetCDF Climate and Forecast (CF) Metadata Conventions Version 1.5 from 25 October, 2010),
 * for details see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html
 *
 * @author Arno Kockx
 */
public class NetcdfUtils {

	//attribute names.
	public static final String STANDARD_NAME_ATTRIBUTE_NAME = "standard_name";
	public static final String LONG_NAME_ATTRIBUTE_NAME = "long_name";
	public static final String UNITS_ATTRIBUTE_NAME = "units";
	public static final String CALENDAR_ATTRIBUTE_NAME = "calendar";
	public static final String AXIS_ATTRIBUTE_NAME = "axis";
	public static final String FILL_VALUE_ATTRIBUTE_NAME = "_FillValue";
	public static final String MISSING_VALUE_ATTRIBUTE_NAME = "missing_value";
	public static final String SCALE_FACTOR_ATTRIBUTE_NAME = "scale_factor";
	public static final String ADD_OFFSET_ATTRIBUTE_NAME = "add_offset";
	public static final String COORDINATES_ATTRIBUTE_NAME = "coordinates";

	//attribute values.
	//see http://www.unidata.ucar.edu/software/netcdf/docs/netcdf.html#NetCDF-Classic-Format for default _FillValues for each dataType.
	private static final double DEFAULT_FILL_VALUE_DOUBLE = 9.96921e+036d;
	private static final String DEFAULT_CALENDAR_ATTRIBUTE_VALUE = "gregorian";
	public static final String T_AXIS = "T";
	public static final String X_AXIS = "X";
	public static final String Y_AXIS = "Y";
	public static final String PROJECTION_Y_COORDINATE = "projection_y_coordinate";
	public static final String PROJECTION_X_COORDINATE = "projection_x_coordinate";

	//variable names.
	public static final String TIME_VARIABLE_NAME = "time";
	public static final String LATITUDE_VARIABLE_NAME = "lat";
	public static final String LATITUDE_STANDARD_NAME = "latitude";
	public static final String LATITUDE_LONG_NAME = "latitude";
	public static final String LONGITUDE_VARIABLE_NAME = "lon";
	public static final String LONGITUDE_STANDARD_NAME = "longitude";
	public static final String LONGITUDE_LONG_NAME = "longitude";
	public static final String Y_VARIABLE_NAME = "y";
	public static final String X_VARIABLE_NAME = "x";

	public static final String STATION_DIMENSION_NAME = "stations";
	public static final String STATION_ID_VARIABLE_NAME = "station_id";
	public static final String FACE_DIMENSION_NAME = "n_face";

	/**
	 * Converts the data in the given netcdf file to text format and returns this as a String.
	 * This can be used e.g. in unit tests to compare the data in human readable text format instead of in binary format.
	 *
	 * @param netcdfFile
	 * @param variableNames semicolon delimited list of variables of which the data should be printed.
	 *                      If this is null or empty, then all variables are printed.
	 * @return String with cdl representation of the data.
	 * @throws IOException
	 */
	public static String netcdfFileToString(File netcdfFile, String variableNames) throws IOException {
		boolean printAllVariables = false;
		if (variableNames == null || variableNames.isEmpty()) {
			printAllVariables = true;
			variableNames = null;
		}

		Writer writer = new StringWriter();
		NCdumpW.print(netcdfFile.getAbsolutePath(), writer, printAllVariables, false, false, true, variableNames, null);
		return writer.toString();
	}

	/**
	 * Creates and returns an ITimeInfo object for the given variable in the given netcdfFile.
	 * If no time info available, then returns null.
	 *
	 * @param variable
	 * @param netcdfFile
	 * @param timeInfoCache
	 * @return timeInfo or null.
	 */
	public static IArrayTimeInfo createTimeInfo(Variable variable, NetcdfFile netcdfFile,
			Map<Variable, IArrayTimeInfo> timeInfoCache) {

		Variable timeVariable = findTimeVariableForVariable(variable, netcdfFile);
		if (timeVariable == null) {//if data does not depend on time.
			return null;
		}

		//if data depends on time.
		IArrayTimeInfo timeInfo = timeInfoCache.get(timeVariable);
		if (timeInfo == null) {
			//create timeInfo.
			double[] times;
			try {
				times = readTimes(timeVariable);
			} catch (IOException e) {
				throw new RuntimeException("Error while reading data from netcdf time variable '" + timeVariable.getName()
						+ "'. Message was: " + e.getMessage(), e);
			}
			timeInfo = new TimeInfo(times);
			//cache timeInfo.
			timeInfoCache.put(timeVariable, timeInfo);
		}

		return timeInfo;
	}

	/**
	 * Creates and returns an IGeometryInfo object for the given variable in the given netcdfFile.
	 * If no geometry info available, then returns null.
	 *
	 * @param variable
	 * @param netcdfFile
	 * @return geometryInfo or null.
	 */
	public static IArrayGeometryInfo createGeometryInfo(Variable variable, NetcdfFile netcdfFile) {
		Variable latitudeVariable = findLatitudeVariableForVariable(variable, netcdfFile);
		Variable longitudeVariable = findLongitudeVariableForVariable(variable, netcdfFile);
		//currently only 2D grids are supported.
		if (latitudeVariable == null || longitudeVariable == null) {//if data does not depend on space.
			return null;
		}

		//if 2D grid.
		IQuantityInfo latitudeQuantityInfo = new QuantityInfo(LATITUDE_STANDARD_NAME, latitudeVariable.getUnitsString());
		IQuantityInfo longitudeQuantityInfo = new QuantityInfo(LONGITUDE_STANDARD_NAME, longitudeVariable.getUnitsString());
		List<Dimension> latitudeVariableDimensions = latitudeVariable.getDimensions();
		int[] latitudeValueIndices = new int[latitudeVariableDimensions.size()];
		for (int n = 0; n < latitudeValueIndices.length; n++) {
			latitudeValueIndices[n] = variable.findDimensionIndex(latitudeVariableDimensions.get(n).getName());
		}
		List<Dimension> longitudeVariableDimensions = longitudeVariable.getDimensions();
		int[] longitudeValueIndices = new int[longitudeVariableDimensions.size()];
		for (int n = 0; n < longitudeValueIndices.length; n++) {
			longitudeValueIndices[n] = variable.findDimensionIndex(longitudeVariableDimensions.get(n).getName());
		}
		IArray latitudeArray = (IArray) readData(latitudeVariable);
		IArray longitudeArray = (IArray) readData(longitudeVariable);
        //the latitude and longitude coordinates are stored in the same order as in the netcdf file.
		ArrayGeometryInfo geometryInfo = new ArrayGeometryInfo(latitudeArray, latitudeValueIndices,
				latitudeQuantityInfo, longitudeArray, longitudeValueIndices, longitudeQuantityInfo,null,null,null);
		return geometryInfo;
	}

	/**
	 * Searches all the variables that the given variable depends on,
	 * and returns the first variable that is a valid time variable.
	 * If cannot find a valid time variable, then returns null.
	 *
	 * @param variable
	 * @param netcdfFile
	 * @return timeVariable or null.
	 */
	public static Variable findTimeVariableForVariable(Variable variable, NetcdfFile netcdfFile) {
		List<Dimension> dimensions = variable.getDimensions();
		for (Dimension dimension : dimensions) {
			Variable dimensionVariable = netcdfFile.findVariable(dimension.getName());
			if (dimensionVariable == null || !dimensionVariable.isCoordinateVariable()) {
				continue;
			}

			if(isTimeVariable(dimensionVariable)) {
				return dimensionVariable;
			}
		}

		//search auxiliary coordinate variables.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#coordinate-system):
		//"An application that is trying to find the latitude coordinate of a variable should always look first to see
		//if any of the variable's dimensions correspond to a latitude coordinate variable. If the latitude coordinate
		//is not found this way, then the auxiliary coordinate variables listed by the coordinates attribute should
		//be checked. Note that it is permissible, but optional, to list coordinate variables as well as auxiliary
		//coordinate variables in the coordinates attribute. The axis attribute is not allowed for auxiliary coordinate
		//variables. Auxiliary coordinate variables which lie on the horizontal surface can be identified as such by their
		//dimensions being horizontal. Horizontal dimensions are those whose coordinate variables have an axis attribute
		//of X or Y, or a units attribute indicating latitude and longitude (see Chapter 4, Coordinate Types ).
		String coordinates = getAttributeStringValue(variable, COORDINATES_ATTRIBUTE_NAME);
		if (coordinates != null) {
			String[] strings = coordinates.split("\\s+");
			for (String auxiliaryCoordinateVariableName : strings) {
				Variable auxiliaryCoordinateVariable = netcdfFile.findVariable(auxiliaryCoordinateVariableName);
				if (auxiliaryCoordinateVariable == null) {
					continue;
				}

				if (isTimeVariable(auxiliaryCoordinateVariable)) {
					return auxiliaryCoordinateVariable;
				}
			}
		}

		return null;
	}

	/**
	 * Searches all the variables that the given variable depends on,
	 * and returns the first variable that is a valid latitude variable.
	 * If cannot find a valid latitude variable, then returns null.
	 *
	 * @param variable
	 * @param netcdfFile
	 * @return latitudeVariable or null.
	 */
	private static Variable findLatitudeVariableForVariable(Variable variable, NetcdfFile netcdfFile) {
		//search coordinate variables.
		List<Dimension> dimensions = variable.getDimensions();
		for (Dimension dimension : dimensions) {
			Variable dimensionVariable = netcdfFile.findVariable(dimension.getName());
			if (dimensionVariable == null || !dimensionVariable.isCoordinateVariable()) {
				continue;
			}

			if (isLatitudeVariable(dimensionVariable)) {
				return dimensionVariable;
			}
		}

		//search auxiliary coordinate variables.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#coordinate-system):
		//"An application that is trying to find the latitude coordinate of a variable should always look first to see
		//if any of the variable's dimensions correspond to a latitude coordinate variable. If the latitude coordinate
		//is not found this way, then the auxiliary coordinate variables listed by the coordinates attribute should
		//be checked. Note that it is permissible, but optional, to list coordinate variables as well as auxiliary
		//coordinate variables in the coordinates attribute. The axis attribute is not allowed for auxiliary coordinate
		//variables. Auxiliary coordinate variables which lie on the horizontal surface can be identified as such by their
		//dimensions being horizontal. Horizontal dimensions are those whose coordinate variables have an axis attribute
		//of X or Y, or a units attribute indicating latitude and longitude (see Chapter 4, Coordinate Types ).
		String coordinates = getAttributeStringValue(variable, COORDINATES_ATTRIBUTE_NAME);
		if (coordinates != null) {
			String[] strings = coordinates.split("\\s+");
			for (String auxiliaryCoordinateVariableName : strings) {
				Variable auxiliaryCoordinateVariable = netcdfFile.findVariable(auxiliaryCoordinateVariableName);
				if (auxiliaryCoordinateVariable == null) {
					continue;
				}

				if (isLatitudeVariable(auxiliaryCoordinateVariable)) {
					return auxiliaryCoordinateVariable;
				}
			}
		}

		return null;
	}

	/**
	 * Searches all the variables that the given variable depends on,
	 * and returns the first variable that is a valid longitude variable.
	 * If cannot find a valid longitude variable, then returns null.
	 *
	 * @param variable
	 * @param netcdfFile
	 * @return longitudeVariable or null.
	 */
	private static Variable findLongitudeVariableForVariable(Variable variable, NetcdfFile netcdfFile) {
		//search coordinate variables.
		List<Dimension> dimensions = variable.getDimensions();
		for (Dimension dimension : dimensions) {
			Variable dimensionVariable = netcdfFile.findVariable(dimension.getName());
			if (dimensionVariable == null || !dimensionVariable.isCoordinateVariable()) {
				continue;
			}

			if (isLongitudeVariable(dimensionVariable)) {
				return dimensionVariable;
			}
		}

		//search auxiliary coordinate variables.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#coordinate-system):
		//"An application that is trying to find the latitude coordinate of a variable should always look first to see
		//if any of the variable's dimensions correspond to a latitude coordinate variable. If the latitude coordinate
		//is not found this way, then the auxiliary coordinate variables listed by the coordinates attribute should
		//be checked. Note that it is permissible, but optional, to list coordinate variables as well as auxiliary
		//coordinate variables in the coordinates attribute. The axis attribute is not allowed for auxiliary coordinate
		//variables. Auxiliary coordinate variables which lie on the horizontal surface can be identified as such by their
		//dimensions being horizontal. Horizontal dimensions are those whose coordinate variables have an axis attribute
		//of X or Y, or a units attribute indicating latitude and longitude (see Chapter 4, Coordinate Types ).
		String coordinates = getAttributeStringValue(variable, COORDINATES_ATTRIBUTE_NAME);
		if (coordinates != null) {
			String[] strings = coordinates.split("\\s+");
			for (String auxiliaryCoordinateVariableName : strings) {
				Variable auxiliaryCoordinateVariable = netcdfFile.findVariable(auxiliaryCoordinateVariableName);
				if (auxiliaryCoordinateVariable == null) {
					continue;
				}

				if (isLongitudeVariable(auxiliaryCoordinateVariable)) {
					return auxiliaryCoordinateVariable;
				}
			}
		}

		return null;
	}

	/**
	 * Returns true if the given variable is a valid time (coordinate) variable.
	 *
	 * @param variable
	 * @return whether the given variable is a valid time (coordinate) variable.
	 */
	private static boolean isTimeVariable(Variable variable) {
		//check if axis is "T".
		if ("T".equalsIgnoreCase(getAttributeStringValue(variable, AXIS_ATTRIBUTE_NAME))) {
			return true;
		}

		// check if the variable is frequency (the unit is Hz); if so, return false.
		// This is explicitly written here because getDateUnitFromDimension(variable) does not give null
		// for frequency, as if frequency is a time coordinate variable.
		if (variable.getUnitsString().equalsIgnoreCase("Hz")){
			return false;
		}

		//check if unit of time.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#time-coordinate):
		//"A time coordinate is identifiable from its units string alone.
		//The Udunits routines utScan() and utIsTime() can be used to make this determination."
		//TODO use Udunits routines utScan() and utIsTime() instead of ucar.nc2.units.DateUnit. AK
		if (variable.getUnitsString() != null && !variable.getUnitsString().isEmpty()
				&& getDateUnitFromDimension(variable) != null) {
			//if variable has a valid unit of time.
			return true;
		}

		return false;
	}

	/**
	 * @param var
	 * @return DateUnit converted unit for this <code>var</code>
	 *		 null if conversion to DateUnit throws an exception
	 */
	private static DateUnit getDateUnitFromDimension(Variable var) {
		try {
			String unitString = var.getUnitsString();
			DateUnit unit = new DateUnit(unitString);
			return unit;
		} catch (Exception e) {
			//if the given variable does not have a valid unit of time.
			return null;
		}
	}

	/**
	 * Returns true if the given variable is a valid latitude variable.
	 *
	 * @param variable
	 * @return whether the given variable is a valid latitude variable.
	 */
	private static boolean isLatitudeVariable(Variable variable) {
		//check if axis is "Y".
		if ("Y".equalsIgnoreCase(getAttributeStringValue(variable, AXIS_ATTRIBUTE_NAME))) {
			return true;
		}

		//check if standard name is "latitude".
		if (LATITUDE_STANDARD_NAME.equalsIgnoreCase(getAttributeStringValue(variable, STANDARD_NAME_ATTRIBUTE_NAME))) {
			return true;
		}

		//check if unit of latitude.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#latitude-coordinate):
		//"The recommended unit of latitude is degrees_north.
		//Also acceptable are degree_north, degree_N, degrees_N, degreeN, and degreesN."
		String unitsString = variable.getUnitsString();
		if (unitsString != null && (unitsString.equalsIgnoreCase("degrees_north") || unitsString.equalsIgnoreCase("degree_north")
									|| unitsString.equalsIgnoreCase("degree_N") || unitsString.equalsIgnoreCase("degrees_N")
									|| unitsString.equalsIgnoreCase("degreeN") || unitsString.equalsIgnoreCase("degreesN"))) {
			//if variable has a valid unit of latitude.
			return true;
		}

		return false;
	}

	/**
	 * Returns true if the given variable is a valid longitude variable.
	 *
	 * @param variable
	 * @return whether the given variable is a valid longitude variable.
	 */
	private static boolean isLongitudeVariable(Variable variable) {
		//check if axis is "X".
		if ("X".equalsIgnoreCase(getAttributeStringValue(variable, AXIS_ATTRIBUTE_NAME))) {
			return true;
		}

		//check if standard name is "longitude".
		if (LONGITUDE_STANDARD_NAME.equalsIgnoreCase(getAttributeStringValue(variable, STANDARD_NAME_ATTRIBUTE_NAME))) {
			return true;
		}

		//check if unit of longitude.
		//according to the CF-convention (see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.5/cf-conventions.html#longitude-coordinate):
		//"The recommended unit of longitude is degrees_east.
		//Also acceptable are degree_east, degree_E, degrees_E, degreeE, and degreesE."
		String unitsString = variable.getUnitsString();
		if (unitsString != null && (unitsString.equalsIgnoreCase("degrees_east") || unitsString.equalsIgnoreCase("degree_east")
									|| unitsString.equalsIgnoreCase("degree_E") || unitsString.equalsIgnoreCase("degrees_N")
									|| unitsString.equalsIgnoreCase("degreeE") || unitsString.equalsIgnoreCase("degreesE"))) {
			//if variable has a valid unit of longitude.
			return true;
		}

		return false;
	}

	public static int getLatitudeDimensionIndexForVariable(Variable variable, NetcdfFile netcdfFile) {
		Variable latitudeVariable = NetcdfUtils.findLatitudeVariableForVariable(variable, netcdfFile);
		if (latitudeVariable == null) {
			return -1;
		}

		List<Dimension> latitudeVariableDimensions = latitudeVariable.getDimensions();
		return variable.findDimensionIndex(latitudeVariableDimensions.get(0).getName());
	}

	public static int getDimensionIndexToFlipFor2DGrid(Variable variable, NetcdfFile netcdfFile, GridStartCorner internalGridStartCorner) {
		int latitudeDimensionIndex = NetcdfUtils.getLatitudeDimensionIndexForVariable(variable, netcdfFile);
		if (latitudeDimensionIndex == -1) {
			return -1;
		}

		Variable latitudeVariable = findLatitudeVariableForVariable(variable, netcdfFile);
		if (latitudeVariable == null) {
			return -1;
		}

		double[] latitudeValues = ((IArray) readData(latitudeVariable)).getValuesAsDoubles();
		boolean ascending = isAscending(latitudeValues);
		switch (internalGridStartCorner) {
			case NORTH_WEST:
				if (ascending) {
					return latitudeDimensionIndex;
				} else {
					return -1;
				}
			case SOUTH_WEST:
				if (ascending) {
					return -1;
				} else {
					return latitudeDimensionIndex;
				}
			case UNKNOWN: default:
				return -1;
		}
	}

	/**
	 * Reads the times from the given timeVariable and converts them to MJD
	 * in the returned array.
	 *
	 * @param timeVariable
	 * @return times array.
	 * @throws IOException
	 */
	private static double[] readTimes(Variable timeVariable) throws IOException {
		double[] convertedTimes = new double[0];

		if ((timeVariable != null) && timeVariable.isCoordinateVariable()) {
			//read times.
			ucar.ma2.Array timesArray = timeVariable.read();
			double[] times = (double[]) timesArray.get1DJavaArray(double.class);

			//convert times.
			convertedTimes = new double[times.length];
			DateUnit dateUnit = getDateUnitFromDimension(timeVariable);
			for (int n = 0; n < times.length; n++) {
				Date date = dateUnit.makeDate(times[n]);
				if (date == null) {
					convertedTimes[n] = 0;
					continue;
				}

				long time = date.getTime();
				convertedTimes[n] = Time.milliesToMjd(time);
			}
		}

		return convertedTimes;
	}

	/**
	 * Reads and returns the data from the given variable.
	 *
	 * @param variable
	 * @return Object.
	 */
	public static Object readData(Variable variable) {
		double[] values;
		try {
			values = (double[]) variable.read().get1DJavaArray(double.class);
		} catch (IOException e) {
			throw new RuntimeException("Error while reading data from netcdf variable '" + variable.getName()
					+ "'. Message was: " + e.getMessage(), e);
		}

		//apply scale factor and offset and replace missing values with Double.NaN.
		double missingValue = getMissingValueDouble(variable);
		double scaleFactor = getScaleFactorDouble(variable);
		double offSet = getOffSetDouble(variable);
		NetcdfUtils.convertDoubleValuesFromNetcdf(values, missingValue, scaleFactor, offSet);

		return new Array(values, variable.getShape(), false);
	}

	/**
	 * @param variable
	 * @param origin
	 * @param sizeArray
	 * @param dimensionIndexToFlip index of dimension to flip for read data. If this is -1, then nothing is flipped.
	 * @return double array
	 */
	public static double[] readSelectedData(Variable variable, int[] origin, int[] sizeArray, int dimensionIndexToFlip) {
		ucar.ma2.Array array;
		try {
			array = variable.read(origin, sizeArray);
		} catch (IOException e) {
			throw new RuntimeException("Error while reading data from netcdf variable '" + variable.getName()
					+ "'. Message was: " + e.getMessage(), e);
		} catch (InvalidRangeException e) {
			throw new RuntimeException("Error while reading data from netcdf variable '" + variable.getName()
					+ "'. Message was: " + e.getMessage(), e);
		}
		if (array == null || array.getSize() == 0) {
			return null;
		}

		if (dimensionIndexToFlip != -1) {
			array = array.flip(dimensionIndexToFlip);
		}

		double[] values = (double[]) array.get1DJavaArray(Double.class);

		//apply scale factor and offset and replace missing values with Double.NaN.
		double missingValue = getMissingValueDouble(variable);
		double scaleFactor = getScaleFactorDouble(variable);
		double offSet = getOffSetDouble(variable);
		NetcdfUtils.convertDoubleValuesFromNetcdf(values, missingValue, scaleFactor, offSet);

		return values;
	}

	public static double[] readDataForVariableForSingleLocation(Variable variable, int stationDimensionIndex, int stationIndex) {
		int[] origin = createOrigin(variable);
		int[] sizeArray = variable.getShape();

		//select only the given station.
		origin[stationDimensionIndex] = stationIndex;
		sizeArray[stationDimensionIndex] = 1;
		return readSelectedData(variable, origin, sizeArray, -1);
	}

	public static double[] readDataForVariableForSingleLocationAndRealization(Variable variable, int stationDimensionIndex, int stationIndex, int realizationDimensionIndex, int realizationIndex) {
		int[] origin = createOrigin(variable);
		int[] sizeArray = variable.getShape();

		//select only the given station.
		origin[stationDimensionIndex] = stationIndex;
		sizeArray[stationDimensionIndex] = 1;
		//select only the given realization
		origin[realizationDimensionIndex] = realizationIndex;
		sizeArray[realizationDimensionIndex] = 1;
		return readSelectedData(variable, origin, sizeArray, -1);
	}

	public static double[] readDataForVariableFor2DGridForSingleTime(Variable variable, int timeDimensionIndex, int timeIndex,
			int dimensionIndexToFlip) {
		int[] origin = createOrigin(variable);
		int[] sizeArray = variable.getShape();

		//select only the given time.
		origin[timeDimensionIndex] = timeIndex;
		sizeArray[timeDimensionIndex] = 1;
		return readSelectedData(variable, origin, sizeArray, dimensionIndexToFlip);
	}

	public static double[] readDataForVariableFor2DGridForSingleTimeAndRealization(Variable variable,
		   int realizationDimensionIndex, int realizationIndex, int timeDimensionIndex, int timeIndex, int dimensionIndexToFlip) {
		int[] origin = createOrigin(variable);
		int[] sizeArray = variable.getShape();

		//select only the given time.
		origin[timeDimensionIndex] = timeIndex;
		sizeArray[timeDimensionIndex] = 1;
		//select only the given realization
		origin[realizationDimensionIndex] = realizationIndex;
		sizeArray[realizationDimensionIndex] = 1;
		return readSelectedData(variable, origin, sizeArray, dimensionIndexToFlip);
	}

	public static void writeDataForVariableForSingleTime(NetcdfFileWriteable netcdfFile, Variable variable, int timeDimensionIndex, int timeIndex, double[] values) {
		int[] origin = createOrigin(variable);
		int[] sizeArray = variable.getShape();

		//select only the given time.
		origin[timeDimensionIndex] = timeIndex;
		sizeArray[timeDimensionIndex] = 1;

		writeSelectedData(netcdfFile, variable, origin, sizeArray, values);
	}

    public static void writeDataForVariableForSingleTimeSingleLocation(NetcdfFileWriteable netcdfFile, Variable variable,
            int timeIndex, int stationIndex, double[] values) {
        int[] origin = createOrigin(variable);
        int[] sizeArray = variable.getShape();

        //select only the given time.
        //assume that timeDimensionIndex is always 0 here and stationDimensionIndex is always 1.
        int timeDimensionIndex = 0;
        int stationDimensionIndex = 1;
        origin[timeDimensionIndex] = timeIndex;
        origin[stationDimensionIndex] = stationIndex;
        sizeArray[timeDimensionIndex] = 1;
        sizeArray[stationDimensionIndex] = 1;

        writeSelectedData(netcdfFile, variable, origin, sizeArray, values);
    }

    private static void writeSelectedData(NetcdfFileWriteable netcdfFile, Variable variable, int[] origin, int[] sizeArray, double[] values) {
		//replace NaN values with missing value for variable.
		double missingValue = getMissingValueDouble(variable);
		if (!Double.isNaN(missingValue)) {
			double[] newValues = new double[values.length];
			System.arraycopy(values, 0, newValues, 0, values.length);
			for (int n = 0; n < newValues.length; n++) {
				if (Double.isNaN(newValues[n])) {
					newValues[n] = missingValue;
				}
			}
			values = newValues;
		} else {//if missingValue is Double.NaN, then NaN values in values array are already equal to missingValue.
			//do nothing.
		}

		//prepare array for writing.
		ucar.ma2.Array array = ucar.ma2.Array.factory(DataType.DOUBLE, sizeArray, values);

		//write data.
		try {
			netcdfFile.write(variable.getName(), origin, array);
		} catch (IOException e) {
			throw new RuntimeException("Error while writing data to netcdf variable '" + variable.getName()
					+ "' in netcdf file " + netcdfFile.getLocation() + ". Message was: " + e.getMessage(), e);
		} catch (InvalidRangeException e) {
			throw new RuntimeException("Error while writing data to netcdf variable '" + variable.getName()
					+ "' in netcdf file " + netcdfFile.getLocation() + ". Message was: " + e.getMessage(), e);
		}
	}

	/**
	 * @return time unit in the form of minutes since 1970-01-01 00:00:00.0 Z(=<code>timezone</code>)
	 */
	public static String createTimeUnitString() {
		final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S Z");
		//referenceTime = 0 means referenceTime is 1970-01-01 00:00:00.0
		long referenceTime = 0;
		String referenceTimeString = dateFormat.format(referenceTime);
		return "minutes since " + referenceTimeString;
	}

	/**
	 * Returns the trimmed String value of the attribute with the given attributeName for the given variable.
	 *
	 * @param variable
	 * @param attributeName
	 * @return String or null.
	 */
	private static String getAttributeStringValue(Variable variable, String attributeName) {
		Attribute attribute = variable.findAttribute(attributeName);
		if (attribute == null) {
			return null;
		}

		String value = attribute.getStringValue();
		if (value == null) {
			return null;
		}

		return value.trim();
	}

	/**
	 * @param var
	 * @return array of int of size equal to dimension size of <code>var</code>, initialized with first index (0).
	 */
	public static int[] createOrigin(Variable var) {
		int dimensionCount = var.getDimensions().size();
		int[] origin = new int[dimensionCount];
		Arrays.fill(origin, 0);
		return origin;
	}

	/**
	 * Read the data for (optional) variable "station_id" from the netcdf file and store in the stationIdsMap.
	 *
	 * For each external location this method stores the external id (station id) and the index
	 * that is used for the location in the netcdf file in stationIdsMap.
	 *
	 * Code copied and adapted from class nl.wldelft.fews.system.plugin.dataImport.NetcdfTimeSeriesTSParser
	 *
	 * @throws IOException
	 */
	public static void readAndStoreStationIdsMap(NetcdfFile netcdfDataset, Map<Integer, String> stationIdsMap) throws IOException {
		Variable stationIdsVar = netcdfDataset.findVariable(STATION_ID_VARIABLE_NAME);
		if (stationIdsVar == null) return;
		int numberOfDimensions = stationIdsVar.getDimensions().size();
		switch (numberOfDimensions) {
			case 0:
				readAndStoreStationIdVariable(stationIdsVar, stationIdsMap);
				break;
			case 1:
				readAndStoreOneDimensionalStationIdsVariable(netcdfDataset, stationIdsMap);
				break;
			case 2: default:
				readAndStoreTwoDimensionalStationIdsVariable(stationIdsVar, netcdfDataset, stationIdsMap);
				break;
		}
	}

	/**
	 * Read  station id from scalar variable
	 *
	 * Code copied and adapted from class nl.wldelft.fews.system.plugin.dataImport.NetcdfTimeSeriesTSParser
	 *
	 * @param variable
	 * @throws IOException
	 */
	private static void readAndStoreStationIdVariable(Variable variable, Map<Integer, String> stationIdsMap) throws IOException {
		String stationId = variable.readScalarString();
		if (stationId == null) return;
		stationIdsMap.put(0, stationId.trim());
		return;
	}

	/**
	 * Assumption: if stationIdsVar has one dimension, then it is a float array that contains
	 * for each location a number float that is the external locationId.
	 *
	 * Code copied and adapted from class nl.wldelft.fews.system.plugin.dataImport.NetcdfTimeSeriesTSParser
	 *
	 * @throws IOException
	 */
	private static void readAndStoreOneDimensionalStationIdsVariable(NetcdfFile netcdfDataset,
			Map<Integer, String> stationIdsMap) throws IOException {
		float[] stationIdFloats;
		try {
			stationIdFloats = (float[]) netcdfDataset.readSection(STATION_ID_VARIABLE_NAME).copyTo1DJavaArray();
		} catch (IOException e) {
			throw new IOException("Error while reading data for variable " + STATION_ID_VARIABLE_NAME + " from netcdf file: " + e.getMessage());
		} catch (InvalidRangeException e) {
			throw new IOException("Error while reading data for variable " + STATION_ID_VARIABLE_NAME + " from netcdf file: " + e.getMessage());
		}
		if (stationIdFloats == null || stationIdFloats.length < 1) {
			throw new IOException("No stationIds found in variable " + STATION_ID_VARIABLE_NAME + " in netcdf file.");
		}

		for (int index = 0; index < stationIdFloats.length; index++) {
			stationIdsMap.put(index, String.valueOf(stationIdFloats[index]));
		}
	}

	/**
	 * Assumption: if stationIdsVar has two dimensions, then the first dimension is
	 * the location axis and the second dimension is a char array that contains
	 * the characters of the String that is the external locationId.
	 *
	 * Code copied and adapted from class nl.wldelft.fews.system.plugin.dataImport.NetcdfTimeSeriesTSParser
	 *
	 * @param stationIdsVar
	 * @throws IOException
	 */
	private static void readAndStoreTwoDimensionalStationIdsVariable(Variable stationIdsVar, NetcdfFile netcdfDataset,
			Map<Integer, String> stationIdsMap) throws IOException {
		char[] stationIdsCh;
		try {
			stationIdsCh = (char[]) netcdfDataset.readSection(STATION_ID_VARIABLE_NAME).copyTo1DJavaArray();
		} catch (IOException e) {
			throw new IOException("Error while reading data for variable" + STATION_ID_VARIABLE_NAME + " from netcdf file:" + e.getMessage());
		} catch (InvalidRangeException e) {
			throw new IOException("Error while reading data for variable" + STATION_ID_VARIABLE_NAME + " from netcdf file:" + e.getMessage());
		}
		int stations = stationIdsVar.getDimension(0).getLength();
		int nchar = stationIdsVar.getDimension(1).getLength();

		Integer stationNr = 0;
		for (int i = 0; i < stations; i++) {
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < nchar; j++) {
				sb.append(stationIdsCh[j + i * nchar]);
			}
			stationIdsMap.put(stationNr, sb.toString().trim());
			stationNr++;
		}
	}

	/**
	 * Creates metadata for the given exchangeItem in the given netcdfFile, if not present yet.
	 *
	 * @param netcdfFile
	 * @param exchangeItem
	 * @param timeInfoTimeDimensionMap
	 * @param uniqueTimeVariableCount
	 * @param geometryInfoGridVariablePropertiesMap
	 * @param uniqueFaceDimensionCount
	 */
	public static void createMetadata(NetcdfFileWriteable netcdfFile, IExchangeItem exchangeItem,
			Map<ITimeInfo, Dimension> timeInfoTimeDimensionMap, int[] uniqueTimeVariableCount,
			Map<IGeometryInfo, GridVariableProperties> geometryInfoGridVariablePropertiesMap,
			int[] uniqueFaceDimensionCount, int[] uniqueLatLonDimensionCount) {

		ArrayList<Dimension> variableDimensions = new ArrayList<Dimension>();

		//create time coordinate variable, if not present yet.
		ITimeInfo timeInfo = exchangeItem.getTimeInfo();
		if (timeInfo != null && timeInfo.getTimes() != null) {//if variable depends on time.
			Dimension timeDimension = timeInfoTimeDimensionMap.get(timeInfo);

			if (timeDimension == null) {//if timeVariable with correct times not yet present.
				//for each unique timeDimension use a unique numbered variable name, like e.g.
				//time, time2, time3, etc.
				uniqueTimeVariableCount[0]++;
				String postfix = uniqueTimeVariableCount[0] <= 1 ? "" : String.valueOf(uniqueTimeVariableCount[0]);
				String timeVariableName = TIME_VARIABLE_NAME + postfix;

				//create time dimension and variable.
				int timeCount = timeInfo.getTimes().length;
				timeDimension = netcdfFile.addDimension(timeVariableName, timeCount);
				netcdfFile.addVariable(timeVariableName, DataType.DOUBLE, new Dimension[]{timeDimension});
				netcdfFile.addVariableAttribute(timeVariableName, STANDARD_NAME_ATTRIBUTE_NAME, TIME_VARIABLE_NAME);
				netcdfFile.addVariableAttribute(timeVariableName, LONG_NAME_ATTRIBUTE_NAME, TIME_VARIABLE_NAME);
				netcdfFile.addVariableAttribute(timeVariableName, UNITS_ATTRIBUTE_NAME, createTimeUnitString());
				//use default calendar.
				netcdfFile.addVariableAttribute(timeVariableName, CALENDAR_ATTRIBUTE_NAME, DEFAULT_CALENDAR_ATTRIBUTE_VALUE);
				netcdfFile.addVariableAttribute(timeVariableName, AXIS_ATTRIBUTE_NAME, T_AXIS);

				//put timeDimension in map so that it can be re-used later by other variables in the same file.
				timeInfoTimeDimensionMap.put(timeInfo, timeDimension);
			}

			variableDimensions.add(timeDimension);
		}

		//create spatial coordinate variables, if not present yet.
		//this only adds spatial dimensions, this does not add spatial variables with coordinates,
		//because the coordinates are usually not available in exchangeItems that come from models.
		IGeometryInfo geometryInfo = exchangeItem.getGeometryInfo();
		if (geometryInfo != null) {//if variable depends on space.
			GridVariableProperties gridVariableProperties = geometryInfoGridVariablePropertiesMap.get(geometryInfo);

			if (geometryInfo instanceof IrregularGridGeometryInfo) {
				Dimension faceDimension;
				if (gridVariableProperties != null) {
					faceDimension = gridVariableProperties.getDimensions().get(0);

				} else {//if face dimension not yet present.
					//for each unique faceDimension use a unique numbered variable name, like e.g.
					//face, face2, face3, etc.
					uniqueFaceDimensionCount[0]++;
					String postfix = uniqueFaceDimensionCount[0] <= 1 ? "" : String.valueOf(uniqueFaceDimensionCount[0]);
					String faceDimensionName = FACE_DIMENSION_NAME + postfix;
					
					//create face dimension.
					int gridCellCount = ((IrregularGridGeometryInfo) geometryInfo).getCellCount();
					faceDimension = netcdfFile.addDimension(faceDimensionName, gridCellCount);
					
					//put timeDimension in map so that it can be re-used later by other variables in the same file.
					gridVariableProperties = new GridVariableProperties();
					gridVariableProperties.setDimensions(Arrays.asList(new Dimension[]{faceDimension}));
					geometryInfoGridVariablePropertiesMap.put(geometryInfo, gridVariableProperties);
				}
				variableDimensions.add(faceDimension);

			} else if (geometryInfo instanceof ArrayGeometryInfo) {
				List<Dimension> dimensions;
				if (gridVariableProperties != null) {
					dimensions = gridVariableProperties.getDimensions();

				} else {//if lat,lon dimensions not yet present.
					//for each unique set of dimensions use a unique numbered variable name, like e.g.
					//lat, lon; lat2, lon2; lat3, lon3; etc.
					uniqueLatLonDimensionCount[0]++;
					String postfix = uniqueLatLonDimensionCount[0] <= 1 ? "" : String.valueOf(uniqueLatLonDimensionCount[0]);
					String yDimensionName = Y_VARIABLE_NAME + postfix;
					String xDimensionName = X_VARIABLE_NAME + postfix;

					//create y,x dimensions.
					int rowCount = ((ArrayGeometryInfo) geometryInfo).getLatitudeArray().length();
					int columnCount = ((ArrayGeometryInfo) geometryInfo).getLongitudeArray().length();
					Dimension yDimension = netcdfFile.addDimension(yDimensionName, rowCount);
					Dimension xDimension = netcdfFile.addDimension(xDimensionName, columnCount);
					dimensions = Arrays.asList(new Dimension[]{yDimension, xDimension});

					//put dimensions in map so that these can be re-used later by other variables in the same file.
					gridVariableProperties = new GridVariableProperties();
					gridVariableProperties.setDimensions(dimensions);
					geometryInfoGridVariablePropertiesMap.put(geometryInfo, gridVariableProperties);

					IQuantityInfo yQuantityInfo = ((ArrayGeometryInfo) geometryInfo).getLatitudeQuantityInfo();
					IQuantityInfo xQuantityInfo = ((ArrayGeometryInfo) geometryInfo).getLongitudeQuantityInfo();
					if (yQuantityInfo != null && xQuantityInfo != null) {
						//create x and y variables.
						createYX1DVariables(netcdfFile, yDimension, xDimension,
								PROJECTION_Y_COORDINATE, PROJECTION_X_COORDINATE,
								yQuantityInfo.getQuantity(), xQuantityInfo.getQuantity(),
								yQuantityInfo.getUnit(), xQuantityInfo.getUnit(),
								gridVariableProperties);

						//TODO add grid mapping variable. This requires information about the coordinate system used for the grid. AK
//						//create grid mapping variable.
//						String gridMappingVariableName = GRID_MAPPING_VARIABLE_NAME + postfix;
//						gridVariableProperties.setGridMappingVariableName(gridMappingVariableName);
//						if (UTM) {
//							createGridMappingVariableTransverseMercator(netcdfFile, gridMappingVariableName);
//						} else {
//							//if grid is rectangular in a system that is not described in the CF-1.6 convention
//							//(see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.6/cf-conventions.html#appendix-grid-mappings).
//							//In this case supply x and y coordinates, but not possible to add a grid mapping,
//							//otherwise the file would not conform to the CF-1.6 convention.
//							//In this case programs that have their own grid definition (e.g. particular models) can just read the
//							//x and y variables, because they do not need a grid mapping.
//
//							//do nothing.
//						}
					}
				}
				variableDimensions.addAll(dimensions);

			} else {
				//TODO implement for other grid geometries. AK
			}
		}

		//create data variable.
            IQuantityInfo quantityInfo = exchangeItem.getQuantityInfo();
            if (quantityInfo == null) {
                throw new RuntimeException(NetcdfUtils.class.getSimpleName()
                        + ": can only write data for exchangeItems that contain a valid IQuantityInfo object.");
            }
            String dataVariableName = quantityInfo.getQuantity();
            DataType dataType;
            switch (exchangeItem.getValuesType()) {
                case doubles2dType: case doublesType: case doubleType: case floatsType: case intType: case IArrayType: case IVectorType:
                    //currently only double values are used in OpenDA, so always write numerical values as doubles for simplicity.
                    dataType = DataType.DOUBLE;
                    break;
                case StringType:
                    dataType = DataType.STRING;
                    break;
			default:
				throw new IllegalArgumentException("Unknown values type " + exchangeItem.getValuesType());
		}
		netcdfFile.addVariable(dataVariableName, dataType, variableDimensions);
		//at this point standard_name of data is unknown, so cannot add the optional standard_name attribute here.
		netcdfFile.addVariableAttribute(dataVariableName, LONG_NAME_ATTRIBUTE_NAME, dataVariableName);
		netcdfFile.addVariableAttribute(dataVariableName, UNITS_ATTRIBUTE_NAME, quantityInfo.getUnit());
		netcdfFile.addVariableAttribute(dataVariableName, FILL_VALUE_ATTRIBUTE_NAME, DEFAULT_FILL_VALUE_DOUBLE);
		//TODO add grid mapping attribute. AK
//		if (geometryInfo != null) {
//			GridVariableProperties gridVariableProperties = geometryInfoGridVariablePropertiesMap.get(geometryInfo);
//			String gridMappingVariableName = gridVariableProperties.getGridMappingVariableName();
//			if (gridMappingVariableName != null) {
//				netcdfFile.addVariableAttribute(dataVariableName, GRID_MAPPING_ATTRIBUTE, gridMappingVariableName);
//			}
//		}
	}

    /**
     * Creates metadata for the given array of exchangeItems in the given netcdfFile, if not present yet.
     * Each exchangeItem stores a scalar timeseries.
     *
     * @param netcdfFile
     * @param exchangeItems
     * @param timeInfoTimeDimensionMap
     * @param uniqueTimeVariableCount
     * @param stationidDimensionMap
     */
    public static void createScalarMetadata(NetcdfFileWriteable netcdfFile, IExchangeItem[] exchangeItems,
                                      Map<ITimeInfo, Dimension> timeInfoTimeDimensionMap, int[] uniqueTimeVariableCount,
                                      List<String> stationidDimensionMap) {

        ArrayList<Dimension> variableDimensions = new ArrayList<Dimension>();

        //create time coordinate variable, if not present yet.
        //assume that all exchangeItems have identical time records.
        ITimeInfo timeInfo = exchangeItems[0].getTimeInfo();
        if (timeInfo != null && timeInfo.getTimes() != null) {//if variable depends on time.
            Dimension timeDimension = timeInfoTimeDimensionMap.get(timeInfo);

            if (timeDimension == null) {//if timeVariable with correct times not yet present.
                //for each unique timeDimension use a unique numbered variable name, like e.g.
                //time, time2, time3, etc.
                uniqueTimeVariableCount[0]++;
                String postfix = uniqueTimeVariableCount[0] <= 1 ? "" : String.valueOf(uniqueTimeVariableCount[0]);
                String timeVariableName = TIME_VARIABLE_NAME + postfix;

                //create time dimension and variable.
                int timeCount = timeInfo.getTimes().length;
                timeDimension = netcdfFile.addDimension(timeVariableName, timeCount);
                netcdfFile.addVariable(timeVariableName, DataType.DOUBLE, new Dimension[]{timeDimension});
                netcdfFile.addVariableAttribute(timeVariableName, STANDARD_NAME_ATTRIBUTE_NAME, TIME_VARIABLE_NAME);
                netcdfFile.addVariableAttribute(timeVariableName, LONG_NAME_ATTRIBUTE_NAME, TIME_VARIABLE_NAME);
                netcdfFile.addVariableAttribute(timeVariableName, UNITS_ATTRIBUTE_NAME, createTimeUnitString());
                //use default calendar.
                netcdfFile.addVariableAttribute(timeVariableName, CALENDAR_ATTRIBUTE_NAME, DEFAULT_CALENDAR_ATTRIBUTE_VALUE);
                netcdfFile.addVariableAttribute(timeVariableName, AXIS_ATTRIBUTE_NAME, T_AXIS);

                //put timeDimension in map so that it can be re-used later by other variables in the same file.
                timeInfoTimeDimensionMap.put(timeInfo, timeDimension);
            }

            variableDimensions.add(timeDimension);
        }

        //create dimension char_leng_id
        int nCharLengId = 30;
        String charLengId = "char_leng_id";
        Dimension char_leng_id = netcdfFile.addDimension(charLengId,nCharLengId);

        //create station_id
        //put stationidDimension in list so that it can be re-used later by other variables in the same file.
        for (int i=0; i<exchangeItems.length; i++){
            IQuantityInfo quantityInfo = exchangeItems[i].getQuantityInfo();
            if (quantityInfo == null) {
                throw new RuntimeException(NetcdfUtils.class.getSimpleName()
                        + ": can only write data for exchangeItems that contain a valid IQuantityInfo object.");
            }
            String dataVariableName = quantityInfo.getQuantity();
            String stationId= exchangeItems[i].getId().replace("."+dataVariableName,"");
            if (!stationidDimensionMap.contains(stationId)){
                stationidDimensionMap.add(stationId);
            }
        }
        int statCount = stationidDimensionMap.size();
        Dimension stationsDimension = netcdfFile.addDimension(STATION_DIMENSION_NAME,statCount);
        String stationIdVariableName = STATION_ID_VARIABLE_NAME;
        netcdfFile.addVariable(stationIdVariableName, DataType.CHAR, new Dimension[]{stationsDimension,char_leng_id});
        netcdfFile.addVariableAttribute(stationIdVariableName, LONG_NAME_ATTRIBUTE_NAME, "station identification code");
        variableDimensions.add(stationsDimension);

        //create data variable.
        List<String> dataVariableNames = new ArrayList<String>();
        for (int i=0; i<exchangeItems.length; i++){
            IQuantityInfo quantityInfo = exchangeItems[i].getQuantityInfo();
            if (quantityInfo == null) {
                throw new RuntimeException(NetcdfUtils.class.getSimpleName()
                        + ": can only write data for exchangeItems that contain a valid IQuantityInfo object.");
            }
            String dataVariableName = quantityInfo.getQuantity();
            DataType dataType;
            switch (exchangeItems[i].getValuesType()) {
                case doubles2dType: case doublesType: case doubleType: case floatsType: case intType: case IArrayType: case IVectorType:
                    //currently only double values are used in OpenDA, so always write numerical values as doubles for simplicity.
                    dataType = DataType.DOUBLE;
                    break;
                case StringType:
                    dataType = DataType.STRING;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown values type " + exchangeItems[i].getValuesType());
            }
            if (!dataVariableNames.contains((String) dataVariableName)){
                netcdfFile.addVariable(dataVariableName, dataType, variableDimensions);
                //at this point standard_name of data is unknown, so cannot add the optional standard_name attribute here.
                netcdfFile.addVariableAttribute(dataVariableName, LONG_NAME_ATTRIBUTE_NAME, dataVariableName);
                netcdfFile.addVariableAttribute(dataVariableName, UNITS_ATTRIBUTE_NAME, quantityInfo.getUnit());
                netcdfFile.addVariableAttribute(dataVariableName, FILL_VALUE_ATTRIBUTE_NAME, DEFAULT_FILL_VALUE_DOUBLE);
            }
            dataVariableNames.add(i,dataVariableName);
        }

    }

    /**
	 * Creates y and x variables with the given properties.
	 *
	 * @param dataFile
	 * @param yDimension
	 * @param xDimension
	 * @param yStandardName
	 * @param xStandardName
	 * @param yLongName
	 * @param xLongName
	 * @param yUnits
	 * @param xUnits
	 * @param gridVariableProperties
	 */
	private static void createYX1DVariables(NetcdfFileWriteable dataFile,
			Dimension yDimension, Dimension xDimension, String yStandardName, String xStandardName,
			String yLongName, String xLongName, String yUnits, String xUnits, GridVariableProperties gridVariableProperties) {

		String yVariableName = yDimension.getName();
		String xVariableName = xDimension.getName();
		createCoordinateVariable(dataFile, yVariableName, yDimension, yStandardName, yLongName, yUnits, Y_AXIS,
				DEFAULT_FILL_VALUE_DOUBLE);
		createCoordinateVariable(dataFile, xVariableName, xDimension, xStandardName, xLongName, xUnits, X_AXIS,
				DEFAULT_FILL_VALUE_DOUBLE);
		gridVariableProperties.setY1DVariableName(yVariableName);
		gridVariableProperties.setX1DVariableName(xVariableName);
	}

	/**
	 * General method to create a one dimensional coordinate variable with the given properties.
	 *
	 * @param dataFile
	 * @param variableName
	 * @param dimension
	 * @param standardName
	 * @param longName
	 * @param units
	 * @param axis
	 */
	private static void createCoordinateVariable(NetcdfFileWriteable dataFile,
			String variableName, Dimension dimension, String standardName,
			String longName, String units, String axis, double fillValue) {

		ArrayList<Dimension> dimensions = new ArrayList<Dimension>();
		dimensions.add(dimension);
		dataFile.addVariable(variableName, DataType.DOUBLE, dimensions);
		dataFile.addVariableAttribute(variableName, STANDARD_NAME_ATTRIBUTE_NAME, standardName);
		dataFile.addVariableAttribute(variableName, LONG_NAME_ATTRIBUTE_NAME, longName);
		dataFile.addVariableAttribute(variableName, UNITS_ATTRIBUTE_NAME, units);
		dataFile.addVariableAttribute(variableName, AXIS_ATTRIBUTE_NAME, axis);
		dataFile.addVariableAttribute(variableName, FILL_VALUE_ATTRIBUTE_NAME, fillValue);
	}

    /**
     * Writes all metadata variables for the given maps to the given netcdfFile,
     * i.e. times and spatial coordinates.
     *
     * @param netcdfFile
     * @param timeInfoTimeDimensionMap
     * @param geometryInfoGridVariablePropertiesMap
     * @param stationIdList
     * @throws Exception
     */
    public static void writeMetadata(NetcdfFileWriteable netcdfFile, Map<ITimeInfo, Dimension> timeInfoTimeDimensionMap,
                                     Map<IGeometryInfo, GridVariableProperties> geometryInfoGridVariablePropertiesMap,
                                     List<String> stationIdList) throws Exception {

        //write time variable values.
        NetcdfUtils.writeTimeVariablesValues(netcdfFile, timeInfoTimeDimensionMap);

        //write geometry variable values.
        NetcdfUtils.writeGridVariablesValues(netcdfFile, geometryInfoGridVariablePropertiesMap);

        //write station_id if available.
        NetcdfUtils.writeStationidVariablesValues(netcdfFile, stationIdList);
    }

    private static void writeStationidVariablesValues(NetcdfFileWriteable dataFile,
            List<String> stationIdList) throws IOException, InvalidRangeException {

        if (stationIdList.size()>0){
            ArrayObject.D1 statidsArray = new ArrayObject.D1(String.class, stationIdList.size());
            for (int i=0; i<stationIdList.size(); i++){
                statidsArray.set(i,stationIdList.get(i));
            }
            dataFile.writeStringData(STATION_ID_VARIABLE_NAME, statidsArray);
        }
    }

    /**
	 * Write values for all time variables that are present.
	 *
	 * @param dataFile
	 * @param timeInfoTimeDimensionMap
	 * @throws Exception
	 */
	public static void writeTimeVariablesValues(NetcdfFileWriteable dataFile,
			Map<ITimeInfo, Dimension> timeInfoTimeDimensionMap) throws Exception {

		for (Map.Entry<ITimeInfo, Dimension> entry : timeInfoTimeDimensionMap.entrySet()) {
			ITimeInfo timeInfo = entry.getKey();
			Dimension timeDimension = entry.getValue();

			Variable timeVariable = dataFile.findVariable(timeDimension.getName());
			String timeUnitString = timeVariable.findAttribute(UNITS_ATTRIBUTE_NAME).getStringValue();
			DateUnit dateUnit = new DateUnit(timeUnitString);

			double[] times = timeInfo.getTimes();
			ArrayDouble.D1 timesArray = new ArrayDouble.D1(times.length);
			for (int n = 0; n < times.length; n++) {
				double newTime = dateUnit.makeValue(new Date(Time.mjdToMillies(times[n])));
				timesArray.set(n, newTime);
			}

			dataFile.write(timeDimension.getName(), timesArray);
		}
	}

	/**
	 * Write values for all grid variables that are present.
	 *
	 * @param netcdfFile
	 * @param geometryGridVariablePropertiesMap
	 * @throws Exception
	 */
	public static void writeGridVariablesValues(NetcdfFileWriteable netcdfFile,
			Map<IGeometryInfo, GridVariableProperties> geometryGridVariablePropertiesMap) throws Exception {

		//this currently only writes YX1DVariables and only for ArrayGeometryInfo.
		for (Map.Entry<IGeometryInfo, GridVariableProperties> entry : geometryGridVariablePropertiesMap.entrySet()) {
			IGeometryInfo geometryInfo = entry.getKey();
			GridVariableProperties gridVariableProperties = entry.getValue();

			//write y and x variable values (if present).
			String y1DVariableName = gridVariableProperties.getY1DVariableName();
			String x1DVariableName = gridVariableProperties.getX1DVariableName();
			if (y1DVariableName != null && x1DVariableName != null && geometryInfo instanceof ArrayGeometryInfo) {
				writeYX1DVariableValues(netcdfFile, (ArrayGeometryInfo) geometryInfo, y1DVariableName, x1DVariableName);
			}
		}
	}

	/**
	 * Writes the values of the 1D y and x coordinate variables with the given names.
	 * The values are taken from the given geometry and are written as coordinates
	 * in the coordinate system that is used by the given geometry.
	 *
	 * @param dataFile
	 * @param geometryInfo
	 * @param yVariableName
	 * @param xVariableName
	 * @throws Exception
	 */
	public static void writeYX1DVariableValues(NetcdfFileWriteable dataFile,
			ArrayGeometryInfo geometryInfo, String yVariableName, String xVariableName) throws Exception {

		IArray latitudeArray = geometryInfo.getLatitudeArray();
		int rowCount = latitudeArray.length();
		ArrayDouble.D1 yArray = new ArrayDouble.D1(rowCount);
		for (int index = 0; index < rowCount; index++) {
			double y = latitudeArray.getValueAsDouble(index);
			if (Double.isNaN(y)) {//should never happen.
				y = DEFAULT_FILL_VALUE_DOUBLE;
			}
			yArray.set(index, y);
		}
		dataFile.write(yVariableName, yArray);

		IArray longitudeArray = geometryInfo.getLongitudeArray();
		int columnCount = longitudeArray.length();
		ArrayDouble.D1 xArray = new ArrayDouble.D1(columnCount);
		for (int index = 0; index < columnCount; index++) {
			double x = longitudeArray.getValueAsDouble(index);
			if (Double.isNaN(x)) {//should never happen.
				x = DEFAULT_FILL_VALUE_DOUBLE;
			}
			xArray.set(index, x);
		}
		dataFile.write(xVariableName, xArray);
	}

	/**
	 * @param variable
	 * @return value from the _FillValue (or missing_value) attribute for the given variable.
	 */
	public static double getMissingValueDouble(Variable variable) {
		Attribute attribute = variable.findAttribute(FILL_VALUE_ATTRIBUTE_NAME);
		if (attribute != null) {
			return attribute.getNumericValue().doubleValue();
		}
		attribute = variable.findAttribute(MISSING_VALUE_ATTRIBUTE_NAME);
		if (attribute != null) {
			return attribute.getNumericValue().doubleValue();
		}
		return Double.NaN;
	}

	/**
	 * @param variable
	 * @return value from the scale_factor attribute for the given variable.
	 */
	public static double getScaleFactorDouble(Variable variable) {
        Attribute attribute = variable.findAttribute(SCALE_FACTOR_ATTRIBUTE_NAME);
        if (attribute != null) {
            return attribute.getNumericValue().doubleValue();
        }
        return 1;
	}

	/**
	 * @param variable
	 * @return value from the add_offset attribute for the given variable.
	 */
	public static double getOffSetDouble(Variable variable) {
        Attribute attribute = variable.findAttribute(ADD_OFFSET_ATTRIBUTE_NAME);
        if (attribute != null) {
            return attribute.getNumericValue().doubleValue();
        } else {
            return 0;
        }
	}

	/**
	 * Converts the raw values from a netcdf file to the real values using the given
	 * missingValue, scaleFactor and offset.
	 *
	 * @param netcdfData
	 * @param missingValue
	 * @param scaleFactor
	 * @param offSet
	 */
	private static void convertDoubleValuesFromNetcdf(double[] netcdfData, double missingValue, double scaleFactor, double offSet) {
		//according to the CF-convention:
		//see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.6/cf-conventions.html#missing-data
		//"Applications that process variables that have attributes to indicate both a transformation (via a scale and/or offset)
		//and missing values should first check that a data value is valid, and then apply the transformation."
		//see http://cf-pcmdi.llnl.gov/documents/cf-conventions/1.6/cf-conventions.html#packed-data
		//"After the data values of a variable have been read, they are to be multiplied by the scale_factor,
		//and have add_offset added to them. If both attributes are present, the data are scaled before the offset is added."

		//apply scale factor and offset and replace missing values with Double.NaN.
		if (scaleFactor == 1d && offSet == 0d) {
			//in this case no need to apply the scaleFactor and offset.
			if (!Double.isNaN(missingValue)) {
				for (int i = 0; i < netcdfData.length; i++) {
					if (Double.compare(netcdfData[i], missingValue) == 0) {
						netcdfData[i] = Double.NaN;
					}
				}
			} else {//if missingValue is Double.NaN, then missing values in netcdfData array are already equal to Double.NaN.
				//do nothing.
			}
		} else {
			for (int i = 0; i < netcdfData.length; i++) {
				if (Double.compare(netcdfData[i], missingValue) == 0) {
					netcdfData[i] = Double.NaN;
				} else {
					netcdfData[i] = netcdfData[i] * scaleFactor + offSet;
				}
			}
		}
	}

    private static boolean isAscending(double[] array) {
        if (array.length <= 1) return true;

        double lastValue = array[0];
        for (int i = 1; i < array.length; i++) {
            double v = array[i];
            if (v <= lastValue) return false;
            lastValue = v;
        }

        return true;
    }
}
