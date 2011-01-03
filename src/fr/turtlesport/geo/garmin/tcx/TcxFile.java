package fr.turtlesport.geo.garmin.tcx;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.Attributes;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import fr.turtlesport.db.DataRun;
import fr.turtlesport.db.DataRunLap;
import fr.turtlesport.db.DataRunTrk;
import fr.turtlesport.db.RunLapTableManager;
import fr.turtlesport.db.RunTrkTableManager;
import fr.turtlesport.geo.AbstractGeoRoute;
import fr.turtlesport.geo.GeoConvertException;
import fr.turtlesport.geo.GeoLoadException;
import fr.turtlesport.geo.IGeoConvertRun;
import fr.turtlesport.geo.IGeoFile;
import fr.turtlesport.geo.IGeoPositionWithAlt;
import fr.turtlesport.geo.IGeoRoute;
import fr.turtlesport.geo.IGeoSegment;
import fr.turtlesport.geo.garmin.Lap;
import fr.turtlesport.geo.garmin.Position;
import fr.turtlesport.geo.garmin.Track;
import fr.turtlesport.geo.garmin.TrackPoint;
import fr.turtlesport.geo.gpx.GpxGeoConvertException;
import fr.turtlesport.lang.LanguageManager;
import fr.turtlesport.log.TurtleLogger;
import fr.turtlesport.util.GeoUtil;
import fr.turtlesport.util.Location;
import fr.turtlesport.util.XmlUtil;

/**
 * Training Center Database v2.
 * 
 * @author Denis Apparicio
 * 
 */
public class TcxFile implements IGeoFile, IGeoConvertRun {
  private static TurtleLogger  log;
  static {
    log = (TurtleLogger) TurtleLogger.getLogger(TcxFile.class);
  }

  /** Extensions. */
  public static final String[] EXT = { "tcx" };

  /**
   * 
   */
  public TcxFile() {
    super();
  }

  /*
   * (non-Javadoc)
   * 
   * @see fr.turtlesport.geo.IGeo#description()
   */
  public String description() {
    return "Garmin Training Center Database v2 (*.tcx)";
  }

  /*
   * (non-Javadoc)
   * 
   * @see fr.turtlesport.geo.IGeo#extension()
   */
  public String[] extension() {
    return EXT;
  }

  /*
   * (non-Javadoc)
   * 
   * @see fr.turtlesport.geo.IGeoConvertRun#convert(fr.turtlesport.db.DataRun,
   *      java.io.File)
   */
  public File convert(DataRun data, File file) throws GeoConvertException,
                                              SQLException {
    log.debug(">>convert");

    if (data == null) {
      throw new IllegalArgumentException("dataRun est null");
    }
    if (file == null) {
      throw new IllegalArgumentException("file est null");
    }

    // Recuperation des points des tours intermediaires.
    DataRunLap[] laps = RunLapTableManager.getInstance().findLaps(data.getId());
    if (laps != null && laps.length < 1) {
      return null;
    }

    long startTime = System.currentTimeMillis();

    BufferedWriter writer = null;
    boolean isError = false;
    try {
      writer = new BufferedWriter(new FileWriter(file));
      SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

      // begin
      writeBegin(writer);
      writeln(writer);

      // Activities
      writer.write("<Activities>");
      writeln(writer);

      // Activity
      writer.write("<Activity ");
      writeSportType(writer, data);
      writer.write(">");
      writeln(writer);
      writer.write("<Id>");
      writer.write(timeFormat.format(data.getTime()));
      writer.write("</Id>");
      writeln(writer);

      // Ecriture des tours intermediaires.
      for (DataRunLap l : laps) {
        writeLap(writer, data, l, timeFormat);
      }

      // end
      writer.write("</Activity>");
      writeln(writer);
      writer.write("</Activities>");
      writeln(writer);
      writeEnd(writer);
    }
    catch (IOException e) {
      log.error("", e);
      isError = true;
      throw new GpxGeoConvertException(e);
    }
    catch (SQLException e) {
      log.error("", e);
      isError = true;
      throw e;
    }
    finally {
      if (writer != null) {
        try {
          writer.close();
        }
        catch (IOException e) {
          log.error("", e);
        }
        if (isError) {
          file.delete();
        }
      }
    }

    long endTime = System.currentTimeMillis();
    log.info("Temps pour ecrire gpx : " + (endTime - startTime) + " ms");

    log.debug("<<convert");
    return file;
  }

  /*
   * (non-Javadoc)
   * 
   * @see fr.turtlesport.geo.IGeoConvertRun#convert(fr.turtlesport.db.DataRun)
   */
  public File convert(DataRun data) throws GeoConvertException, SQLException {
    // construction du nom du fichier

    // construction du nom du fichier
    String name = LanguageManager.getManager().getCurrentLang()
        .getDateTimeFormatterWithoutSep().format(data.getTime())
                  + EXT[0];
    File file = new File(Location.googleEarthLocation(), name);

    // conversion
    convert(data, file);
    return file;
  }

  /*
   * (non-Javadoc)
   * 
   * @see fr.turtlesport.geo.IGeoFile#load(java.io.File)
   */
  public IGeoRoute[] load(File file) throws GeoLoadException,
                                    FileNotFoundException {
    log.debug(">>load");

    IGeoRoute[] rep;

    // Lecture
    FileInputStream fis = new FileInputStream(file);

    TcxHandler handler = null;
    try {
      // Validation schema
      SchemaFactory factory = SchemaFactory
          .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      StreamSource ss = new StreamSource(getClass()
          .getResourceAsStream("TrainingCenterDatabasev2.xsd"));
      Schema schema = factory.newSchema(ss);
      Validator validator = schema.newValidator();
      validator.validate(new StreamSource(file));

      // Recuperation du parser
      SAXParserFactory spf = SAXParserFactory.newInstance();
      spf.setNamespaceAware(true);

      // parsing
      SAXParser parser = spf.newSAXParser();
      handler = new TcxHandler();
      parser.parse(fis, handler);

      // construction de la reponse
      if (handler.listActivity != null) {
        rep = new IGeoRoute[handler.listActivity.size()];
      }
      else {
        rep = new IGeoRoute[0];
      }

      // construction de la reponse
      ArrayList<IGeoRoute> list = new ArrayList<IGeoRoute>();
      if (handler.listActivity != null) {
        for (Activity actv : handler.listActivity) {
          list.add(new ActivityGeoRoute(actv));
        }
      }
      rep = new IGeoRoute[list.size()];
      if (list.size() > 0) {
        list.toArray(rep);
      }
    }
    catch (Throwable e) {
      log.error("", e);
      throw new GeoLoadException(e);
    }

    log.debug("<<load");
    return rep;
  }

  private void writeBegin(BufferedWriter writer) throws IOException {
    writer
        .write("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">");
  }

  private void writeln(BufferedWriter writer) throws IOException {
    writer.write("\n");
  }

  private void writeSportType(BufferedWriter writer, DataRun data) throws IOException {
    writer.write("Sport=\"");
    if (data.isSportRunning()) {
      writer.write("Running");
    }
    else if (data.isSportBike()) {
      writer.write("Biking");
    }
    else {
      writer.write("Other");
    }
    writer.write("\"");
  }

  private void writeEnd(BufferedWriter writer) throws IOException {
    // writer.write("<Author xsi:type=\"Application_t\">");
    // writer.write("<Name>Turtle Sport</Name>");
    // writer.write("<Build>");
    // writer.write("<Version>");
    // writer.write("<VersionMajor>" + Version.VERSION_MAJOR +
    // "</VersionMajor>");
    // writer.write("<VersionMinor>" + Version.VERSION_MINOR +
    // "</VersionMinor>");
    // writer.write("</Version>");
    // writer.write("<Type>Release</Type>");
    // writer.write("</Build>");
    // writer.write("<LangID>FR</LangID>");
    // writer.write("<PartNumber>0</PartNumber>");
    // writer.write("</Author>");
    writeln(writer);
    writer.write("</TrainingCenterDatabase>");
  }

  private void writeTrkPoint(BufferedWriter writer,
                             DataRunTrk point,
                             SimpleDateFormat timeFormat) throws IOException {
    writer.write("<Trackpoint>");

    // Time
    writer.write("<Time>" + timeFormat.format(point.getTime()) + "</Time>");
    // position
    double latitude = GeoUtil.makeLatitudeFromGarmin(point.getLatitude());
    double longitude = GeoUtil.makeLatitudeFromGarmin(point.getLongitude());
    writer.write("<Position>");
    writer.write("<LatitudeDegrees>" + latitude + "</LatitudeDegrees>");
    writer.write("<LongitudeDegrees>" + longitude + "</LongitudeDegrees>");
    writer.write("</Position>");
    // Altitude
    writer
        .write("<AltitudeMeters>" + point.getAltitude() + "</AltitudeMeters>");
    // DistanceMeters
    writer
        .write("<DistanceMeters>" + point.getDistance() + "</DistanceMeters>");
    // HeartRateBpm
    if (point.getHeartRate() > 0) {
      writer.write("<HeartRateBpm xsi:type=\"HeartRateInBeatsPerMinute_t\">");
      writer.write("<Value>" + point.getHeartRate() + "</Value>");
      writer.write("</HeartRateBpm>");
      writer.write("<SensorState>Present</SensorState>");
    }
    else {
      writer.write("<SensorState>Absent</SensorState>");
    }
    // Cadence
    if (point.isValidCadence()) {
      writer.write("<Cadence>" + point.getCadence() + "</Cadence>");
    }
    writer.write("</Trackpoint>");
  }

  private void writeLap(BufferedWriter writer,
                        DataRun data,
                        DataRunLap l,
                        SimpleDateFormat timeFormat) throws IOException,
                                                    SQLException {
    log.debug(">>writeLap");

    // recuperation des points du tour
    Date dateEnd = new Date(l.getStartTime().getTime() + l.getTotalTime() * 10);
    DataRunTrk[] trks = RunTrkTableManager.getInstance()
        .getTrks(data.getId(), l.getStartTime(), dateEnd);

    if (trks.length == 0) {
      log.warn("pas de points pour ce tour");
      return;
    }

    // Ecriture
    String startTime = timeFormat.format(l.getStartTime());
    if (log.isDebugEnabled()) {
      log.debug("Lap StartTime=" + startTime);
    }
    writer.write("<Lap StartTime=\"" + startTime + "\">");
    writeln(writer);

    // TotalTimeSeconds
    double totalTime = l.getTotalTime() / 100.0;
    writer.write("<TotalTimeSeconds>" + totalTime + "</TotalTimeSeconds>");
    // DistanceMeters
    writeln(writer);
    writer.write("<DistanceMeters>" + l.getTotalDist() + "</DistanceMeters>");
    // MaximumSpeed
    writeln(writer);
    writer.write("<MaximumSpeed>" + l.getMaxSpeed() + "</MaximumSpeed>");
    // Calories
    writeln(writer);
    writer.write("<Calories>" + l.getCalories() + "</Calories>");
    // AverageHeartRateBpm
    if (l.getAvgHeartRate() > 0) {
      writer.write("<AverageHeartRateBpm>");
      writer.write("<Value>" + l.getAvgHeartRate() + "</Value>");
      writer.write("</AverageHeartRateBpm>");
    }
    // MaximumHeartRateBpm
    if (l.getMaxHeartRate() > 0) {
      writer.write("<MaximumHeartRateBpm>");
      writer.write("<Value>" + l.getMaxHeartRate() + "</Value>");
      writer.write("</MaximumHeartRateBpm>");
    }
    // Intensity
    writeln(writer);
    writer.write("<Intensity>Active</Intensity>");
    // TriggerMethod
    writeln(writer);
    writer.write("<TriggerMethod>Manual</TriggerMethod>");

    // Track
    writeln(writer);
    writer.write("<Track>");
    for (DataRunTrk t : trks) {
      writeln(writer);
      writeTrkPoint(writer, t, timeFormat);
    }

    writeln(writer);
    writer.write("</Track>");
    writeln(writer);
    writer.write("</Lap>");

    log.debug("<<writeLap");
  }

  /**
   * @author Denis Apparicio
   * 
   */
  private class TcxHandler extends DefaultHandler {
    private StringBuffer        stBuffer;

    private ArrayList<Activity> listActivity;

    private Activity            currentActivity;

    private boolean             isActivity            = false;

    private Lap                 currentLap;

    private boolean             isLap                 = false;

    private boolean             isAverageHeartRateBpm = false;

    private boolean             isHeartRateBpm        = false;

    private boolean             isMaximumHeartRateBpm = false;

    private Track               currentTrack;

    private boolean             isTrack               = false;

    private TrackPoint          currentTrackPoint;

    private boolean             isTrackpoint          = false;

    private Position            currentPosition;

    private boolean             isPosition;

    /**
     * 
     */
    public TcxHandler() {
      super();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String,
     *      java.lang.String, java.lang.String, org.xml.sax.Attributes)
     */
    public void startElement(String uri,
                             String localName,
                             String qName,
                             Attributes attrs) throws SAXParseException {
      if (log.isDebugEnabled()) {
        log.debug(">>startElement uri=" + uri + " localName=" + localName
                  + " qName=" + qName);
      }

      // Activities
      if (qName.equals("Activities")) {
        listActivity = new ArrayList<Activity>();
      }

      // Activity
      if (qName.equals("Activity")) {
        // sport
        currentActivity = new Activity(attrs.getValue("Sport"));
        isActivity = true;
      }

      // Lap
      if (qName.equals("Lap") && isActivity) {
        // sport
        currentLap = new Lap(XmlUtil.getTime(attrs.getValue("StartTime")),
                             currentActivity.getLapSize());
        isLap = true;
      }

      // AverageHeartRateBpm
      if (qName.equals("AverageHeartRateBpm") && isLap) {
        isAverageHeartRateBpm = true;
      }

      // AverageHeartRateBpm
      if (qName.equals("MaximumHeartRateBpm") && isLap) {
        isMaximumHeartRateBpm = true;
      }

      // HeartRateBpm
      if (qName.equals("HeartRateBpm") && isTrackpoint) {
        isHeartRateBpm = true;
      }

      // Track
      if (qName.equals("Track") && isLap) {
        isTrack = true;
        currentTrack = new Track();
      }

      // Trackpoint
      if (qName.equals("Trackpoint") && isTrack) {
        isTrackpoint = true;
        currentTrackPoint = new TrackPoint();
      }

      // Position
      if (qName.equals("Position") && isTrackpoint) {
        isPosition = true;
        currentPosition = new Position();
      }

      log.debug("<<startElement");
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    public void endElement(String uri, String localName, String qName) {
      if (log.isDebugEnabled()) {
        log.debug(">>endElement uri=" + uri + " localName=" + localName
                  + " qName=" + qName);
      }

      if (qName.equals("id")) {
        // id
        if (isActivity) {
          currentActivity.setId(XmlUtil.getTime(stBuffer.toString()));
        }
      }
      else if (qName.equals("TotalTimeSeconds") && isLap) { // TotalTimeSeconds
        // isLap
        currentLap.setTotalTimeSeconds(new Double(stBuffer.toString()));
      }
      else if (qName.equals("Time") && isTrackpoint) { // Time
        // Trackpoint
        currentTrackPoint.setDate(XmlUtil.getTime(stBuffer.toString()));
      }
      else if (qName.equals("AltitudeMeters") && isTrackpoint) { // AltitudeMeters
        // Trackpoint
        currentTrackPoint.setElevation(new Double(stBuffer.toString()));
      }
      else if (qName.equals("DistanceMeters")) { // DistanceMeters
        if (isTrackpoint) {
          // TrackPoint
          currentTrackPoint.setDistanceMeters(new Double(stBuffer.toString()));
        }
        else if (isLap) {
          // Lap
          currentLap.setDistanceMeters(new Double(stBuffer.toString()));
        }
      }
      else if (qName.equals("SensorState") && isTrackpoint) { // SensorState
        // TrackPoint
        currentTrackPoint.setSensorState(stBuffer.toString());
      }
      else if (qName.equals("MaximumSpeed") && isLap) { // MaximumSpeed
        // Lap
        currentLap.setMaxSpeed(new Double(stBuffer.toString()));
      }
      else if (qName.equals("Calories") && isLap) { // Calories
        // Lap
        currentLap.setCalories(new Integer(stBuffer.toString()));
      }
      else if (qName.equals("Value")) { // Value
        if (isAverageHeartRateBpm) {
          // AverageHeartRateBpm -> Value
          currentLap.setAverageHeartRateBpm(new Integer(stBuffer.toString()));
        }
        else if (isMaximumHeartRateBpm) {
          // AverageHeartRateBpm -> Value
          currentLap.setMaximumHeartRateBpm(new Integer(stBuffer.toString()));
        }
        else if (isHeartRateBpm) {
          // HeartRateBpm -> Value
          currentTrackPoint.setHeartRate(new Integer(stBuffer.toString()));
        }
      }
      else if (qName.equals("Intensity") && isLap) {// Intensity
        // Lap
        currentLap.setIntensity(stBuffer.toString());
      }
      else if (qName.equals("Cadence")) {// Cadence
        if (isTrackpoint) {
          // TrackPoint
          currentTrackPoint.setCadence(new Integer(stBuffer.toString()));
        }
        else if (isLap) {
          // Lap
          currentLap.setCadence(new Integer(stBuffer.toString()));
        }
      }
      else if (qName.equals("TriggerMethod") && isLap) {// TriggerMethod
        // Lap
        currentLap.setTriggerMethod(stBuffer.toString());
      }
      else if (qName.equals("LatitudeDegrees") && isPosition) { // LatitudeDegrees
        // Position
        currentPosition.setLatitude(new Double(stBuffer.toString()));
      }
      else if (qName.equals("LongitudeDegrees") && isPosition) {// LongitudeDegrees
        // Position
        currentPosition.setLongitude(new Double(stBuffer.toString()));
      }
      stBuffer = null;

      // Activity
      // -------------
      if (qName.equals("Activity")) {
        listActivity.add(currentActivity);
        isActivity = false;
      }

      // Lap
      // ---------
      if (qName.equals("Lap") && isLap) {
        isLap = false;
        currentActivity.addLap(currentLap);
      }

      // Track
      // ---------
      if (qName.equals("Track") && isTrack) {
        isTrack = false;
        currentLap.addTrack(currentTrack);
      }

      // Trackpoint
      // ---------------
      if (qName.equals("Trackpoint") && isTrackpoint) {
        isTrackpoint = false;
        currentTrack.addPoint(currentTrackPoint);
        log.info(currentTrackPoint);
      }

      // Position
      // -------------------
      if (qName.equals("Position") && isPosition) {
        isPosition = false;
        currentTrackPoint.setPosition(currentPosition);
      }

      // AverageHeartRateBpm
      // ----------------------
      if (qName.equals("AverageHeartRateBpm")) {
        isAverageHeartRateBpm = false;
      }

      // MaximumHeartRateBpm
      // ----------------------
      if (qName.equals("MaximumHeartRateBpm")) {
        isMaximumHeartRateBpm = false;
      }

      // HeartRateBpm
      // ----------------------
      if (qName.equals("HeartRateBpm")) {
        isHeartRateBpm = false;
      }

      log.debug("<<endElement");
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
     */
    public void characters(char[] ch, int start, int length) {
      String st = new String(ch, start, length).trim();
      log.debug(">>characters " + st);

      if (st.length() > 0) {
        if (stBuffer == null) {
          stBuffer = new StringBuffer(st);
        }
        else {
          stBuffer.append(st);
        }
      }

      log.debug("<<characters ");
    }
  }

  /**
   * Repr�sente une GEORoute pour une cativite.
   * 
   * @author Denis Apparicio
   * 
   */
  private class ActivityGeoRoute extends AbstractGeoRoute {
    private Activity activity;

    private double   distanceTot = 0;

    /**
     * @param activity
     */
    public ActivityGeoRoute(Activity activity) {
      this.activity = activity;
      if (activity.isRunning()) {
        setSportType(IGeoRoute.SPORT_TYPE_RUNNING);
      }
      else if (activity.isBiking()) {
        setSportType(IGeoRoute.SPORT_TYPE_BIKE);
      }
      else {
        setSportType(IGeoRoute.SPORT_TYPE_OTHER);
      }

      // Distance et temps totale
      double timeTot = 0;
      for (Lap lap : activity.getLaps()) {
        distanceTot += lap.getDistanceMeters();
        timeTot += lap.getTotalTimeSeconds();
      }
      initTimeTot((long) (timeTot * 1000));
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.AbstractGeoRoute#distanceTot()
     */
    @Override
    public double distanceTot() {
      return distanceTot;
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.IGeoRoute#getAllPoints()
     */
    public List<IGeoPositionWithAlt> getAllPoints() {
      ArrayList<IGeoPositionWithAlt> list = new ArrayList<IGeoPositionWithAlt>();
      for (Lap lap : activity.getLaps()) {
        for (Track trk : lap.getTracks()) {
          for (TrackPoint p : trk.getTrackPoints()) {
            list.add(p);
          }
        }
      }
      return list;
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.IGeoRoute#getName()
     */
    public String getName() {
      return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.IGeoRoute#getSegmentSize()
     */
    public int getSegmentSize() {
      return activity.getLapSize();
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.AbstractGeoRoute#getStartTime()
     */
    @Override
    public Date getStartTime() {
      return activity.getLap(0).getStartTime();
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.IGeoRoute#getSegment(int)
     */
    public IGeoSegment getSegment(int index) {
      return activity.getLap(index);
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.turtlesport.geo.IGeoRoute#getSegments()
     */
    public List<IGeoSegment> getSegments() {
      List<IGeoSegment> list = new ArrayList<IGeoSegment>();
      if (activity.getLaps() != null) {
        for (Lap l : activity.getLaps()) {
          list.add(l);
        }
      }
      return list;
    }

  }

}
