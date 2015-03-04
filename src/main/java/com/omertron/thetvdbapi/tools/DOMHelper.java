/*
 *      Copyright (c) 2004-2015 Matthew Altman & Stuart Boston
 *
 *      This file is part of TheTVDB API.
 *
 *      TheTVDB API is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation, either version 3 of the License, or
 *      any later version.
 *
 *      TheTVDB API is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with TheTVDB API.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.omertron.thetvdbapi.tools;

import com.omertron.thetvdbapi.ApiExceptionType;
import com.omertron.thetvdbapi.TvDbException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.scraper.util.CachedUrl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.Charset;

/**
 * Generic set of routines to process the DOM model data
 *
 * @author Stuart.Boston
 *
 */
public class DOMHelper {

  private static final Logger  LOG                   = LoggerFactory.getLogger(DOMHelper.class);
  private static final String  YES                   = "yes";
  private static final String  DEFAULT_CHARSET       = "UTF-8";
  private static final Charset CHARSET               = Charset.forName(DEFAULT_CHARSET);
  private static final int     RETRY_COUNT           = 5;
  // Milliseconds to retry
  private static final int     RETRY_TIME            = 250;
  // Constants
  private static final String  ERROR_WRITING         = "Error writing the document to {}";
  private static final String  ERROR_UNABLE_TO_PARSE = "Unable to parse TheTVDb response, please try again later.";

  // Hide the constructor
  protected DOMHelper() {
    // prevents calls from subclass
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the string value of the tag element name passed
   *
   * @param element
   * @param tagName
   * @return
   */
  public static String getValueFromElement(Element element, String tagName) {
    NodeList elementNodeList = element.getElementsByTagName(tagName);
    if (elementNodeList == null) {
      return "";
    }
    else {
      Element tagElement = (Element) elementNodeList.item(0);
      if (tagElement == null) {
        return "";
      }

      NodeList tagNodeList = tagElement.getChildNodes();
      if (tagNodeList == null || tagNodeList.getLength() == 0) {
        return "";
      }
      return tagNodeList.item(0).getNodeValue();
    }
  }

  /**
   * Get a DOM document from the supplied URL
   *
   * @param url
   * @return
   * @throws com.omertron.thetvdbapi.TvDbException
   */
  public static synchronized Document getEventDocFromUrl(String url) throws TvDbException {
    InputStream in = null;
    Document doc = null;

    try {
      String webPage = getValidWebpage(url);

      if (StringUtils.isNotBlank(webPage)) {
        in = new ByteArrayInputStream(webPage.getBytes(CHARSET));

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();

        doc = db.parse(in);
        in.close();
        doc.getDocumentElement().normalize();
      }
    }
    catch (UnsupportedEncodingException ex) {
      throw new TvDbException(ApiExceptionType.INVALID_URL, "Unable to encode URL", url, ex);
    }
    catch (ParserConfigurationException error) {
      throw new TvDbException(ApiExceptionType.MAPPING_FAILED, ERROR_UNABLE_TO_PARSE, url, error);
    }
    catch (SAXException error) {
      throw new TvDbException(ApiExceptionType.MAPPING_FAILED, ERROR_UNABLE_TO_PARSE, url, error);
    }
    catch (IOException error) {
      throw new TvDbException(ApiExceptionType.MAPPING_FAILED, ERROR_UNABLE_TO_PARSE, url, error);
    }
    finally {
      try {
        if (in != null) {
          in.close();
        }
      }
      catch (IOException ex) {
        // Input Stream was already closed or null
        LOG.trace("Failed to close InputStream", ex);
      }
    }

    return doc;
  }

  private static String getValidWebpage(String url) throws TvDbException {
    // Count the number of times we download the web page
    int retryCount = 0;
    // Is the web page valid
    boolean valid = false;
    String content = "";
    CachedUrl cachedUrl = null;

    while (!valid && (retryCount < RETRY_COUNT)) {
      retryCount++;
      try {
        cachedUrl = new CachedUrl(url);
        StringWriter writer = new StringWriter();
        IOUtils.copy(cachedUrl.getInputStream(), writer);
        content = writer.toString();
        if (StringUtils.isNotBlank(content)) {
          // See if the ID is null
          if (!content.contains("<id>") || content.contains("<id></id>")) {
            // Wait an increasing amount of time the more retries that happen
            waiting(retryCount * RETRY_TIME);
            continue;
          }

          valid = true;
        }
      }
      catch (Exception e) {
        // Couldn't get a valid webPage so, quit.
        throw new TvDbException(ApiExceptionType.UNKNOWN_CAUSE, content, cachedUrl != null ? cachedUrl.getStatusCode() : -1, url);

      }

      if (!valid) {
        throw new TvDbException(ApiExceptionType.UNKNOWN_CAUSE, content, cachedUrl != null ? cachedUrl.getStatusCode() : -1, url);
      }
      return content;
    }

    return null;
  }

  /**
   * Convert a DOM document to a string
   *
   * @param doc
   * @return
   * @throws javax.xml.transform.TransformerException
   */
  public static String convertDocToString(Document doc) throws TransformerException {
    // set up a transformer
    TransformerFactory transfac = TransformerFactory.newInstance();
    Transformer trans = transfac.newTransformer();
    trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, YES);
    trans.setOutputProperty(OutputKeys.INDENT, YES);

    // create string from xml tree
    StringWriter sw = new StringWriter();
    StreamResult result = new StreamResult(sw);
    DOMSource source = new DOMSource(doc);
    trans.transform(source, result);
    return sw.toString();
  }

  /**
   * Write the Document out to a file using nice formatting
   *
   * @param doc
   *          The document to save
   * @param localFile
   *          The file to write to
   * @return
   */
  public static boolean writeDocumentToFile(Document doc, String localFile) {
    try {
      TransformerFactory transfact = TransformerFactory.newInstance();
      Transformer trans = transfact.newTransformer();
      trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, YES);
      trans.setOutputProperty(OutputKeys.INDENT, YES);
      trans.transform(new DOMSource(doc), new StreamResult(new File(localFile)));
      return true;
    }
    catch (TransformerConfigurationException ex) {
      LOG.warn(ERROR_WRITING, localFile, ex);
      return false;
    }
    catch (TransformerException ex) {
      LOG.warn(ERROR_WRITING, localFile, ex);
      return false;
    }
  }

  /**
   * Add a child element to a parent element
   *
   * @param doc
   * @param parentElement
   * @param elementName
   * @param elementValue
   */
  public static void appendChild(Document doc, Element parentElement, String elementName, String elementValue) {
    Element child = doc.createElement(elementName);
    Text text = doc.createTextNode(elementValue);
    child.appendChild(text);
    parentElement.appendChild(child);
  }

  /**
   * Wait for a few milliseconds
   *
   * @param milliseconds
   */
  private static void waiting(int milliseconds) {
    long t0, t1;
    t0 = System.currentTimeMillis();
    do {
      t1 = System.currentTimeMillis();
    } while ((t1 - t0) < milliseconds);
  }
}