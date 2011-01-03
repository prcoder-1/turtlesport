package fr.turtlesport.googleearth;

import java.io.File;

/**
 * @author denis
 * 
 */
public interface IGoogleEarth {

  /**
   * Ouverture d'un fichier gooogle-earth.
   * 
   * @param kmFile
   * @throws GoogleEarthException
   */
  void open(File kmFile) throws GoogleEarthException;

  /**
   * D&eacute;termine si googleEarth est install&eacute; sur ce poste.
   * 
   * @return <code>true</code>si google earth est insatll&eacute;
   */
  boolean isInstalled();

  /**
   * Restitue le path de googleearth.
   * 
   * @return le path de googleearth.
   */
  String getPath();
}
