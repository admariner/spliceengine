/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.derbyBuild.messages;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;


/**
 * <p>
 * This tool generates the engine's message strings (message_en.properties) as well
 * the dita source for the SQLState documentation in the Derby Reference Guide.
 * </p>
 */
public class MessageBuilder {

    private static final String PROPERTIES_BOILERPLATE = "#\n" +
            "###################################################\n" +
            "\n" +
            "###################################################\n" +
            "#\n" +
            "# DO NOT EDIT THIS FILE!\n" +
            "#\n" +
            "# Instead, edit messages.xml. The ant MessageBuilder task takes\n" +
            "# messages.xml as input and from it generates this file.\n" +
            "#\n" +
            "###################################################\n";


    private String xmlSourceFile;
    private String propertiesTargetFile;

    public static void main(String[] arg) {
        MessageBuilder mb = new MessageBuilder();
        mb.xmlSourceFile = arg[0];
        mb.propertiesTargetFile = arg[1];
        mb.execute();
    }

    /**
     * <p>
     * Let Ant conjure us out of thin air.
     * </p>
     */
    public MessageBuilder() {
    }

    /**
     * <p>Let Ant set the input file name.</p>
     */
    public void setXmlSourceFile(String fileName) {
        xmlSourceFile = fileName;
    }

    /**
     * <p>Let Ant set the file name for the message property file we will write.</p>
     */
    public void setPropertiesTargetFile(String fileName) {
        propertiesTargetFile = fileName;
    }


    /**
     * <p>
     * Read the xml message descriptors and output messages_en.properties
     * and the dita source for the SQLState table in the Derby Reference Guide.
     * After setting up arguments using the above setter methods, Ant
     * calls this method in order to run this custom task.
     * </p>
     */
    public void execute() {
        File source = new File(xmlSourceFile);
        File targetProperties = new File(propertiesTargetFile);
        FileWriter propertiesFW = null;
        PrintWriter propertiesPW = null;

        try {
            targetProperties.getParentFile().mkdirs();
            propertiesFW = new FileWriter(targetProperties);
            propertiesPW = new PrintWriter(propertiesFW);

            processMessages(source, propertiesPW);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate English properties from message descriptors.", e);
        } finally {
            try {
                finishWriting(propertiesFW, propertiesPW);
            } catch (Exception ex) {
                throw new IllegalStateException("Error closing file writers.", ex);
            }
        }

    }

    /////////////////////////////////////////////////////////////////////////
    //
    //  MINIONS TO PROCESS MESSAGE DESCRIPTORS
    //
    /////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Loop through descriptors and write appropriate output to the properties
     * and dita files.
     * </p>
     */
    private void processMessages(File input, PrintWriter propertiesPW) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(input);
        Element root = doc.getDocumentElement();    // framing "messages" element
        NodeList sections = root.getElementsByTagName("section");

        propertiesPW.println(PROPERTIES_BOILERPLATE);
        processSections(propertiesPW, sections);
    }

    /**
     * <p>
     * Loop through sections in the message descriptor file..
     * </p>
     */
    private void processSections(PrintWriter propertiesPW, NodeList nodes) throws Exception {
        int nodeCount = nodes.getLength();

        for (int i = 0; i < nodeCount; i++) {
            Element node = (Element) nodes.item(i);

            processSection(propertiesPW, node);
        }
    }

    /**
     * <p>
     * Read a section from the message descriptor file.
     * </p>
     */
    private void processSection(PrintWriter propertiesPW, Element section) throws Exception {
        NodeList families = section.getElementsByTagName("family");
        int familyCount = families.getLength();

        for (int i = 0; i < familyCount; i++) {
            Element family = (Element) families.item(i);
            processFamily(propertiesPW, family);
        }
    }

    /**
     * <p>
     * Read a family of message descriptors
     * </p>
     */
    private void processFamily(PrintWriter propertiesPW, Element family) throws Exception {
        String title = squeezeText(getFirstChild(family, "title"));
        NodeList messages = family.getElementsByTagName("msg");
        int messageCount = messages.getLength();

        for (int i = 0; i < messageCount; i++) {
            Element message = (Element) messages.item(i);

            processMessage(propertiesPW, message);
        }
    }

    /**
     * <p>
     * Read and process a message.
     * </p>
     */
    private void processMessage(PrintWriter propertiesPW, Element message) throws Exception {
        String name = squeezeText(getFirstChild(message, "name"));
        String sqlstate = getSQLState(name);
        String rawText = squeezeText(getFirstChild(message, "text"));
        String propertyText = escapePropertiesText(rawText);
        int parameterCount = countParameters(rawText);
        String[] args = getArgs(message);

        if (parameterCount != args.length) {
            throw new Exception(name + " has " + parameterCount + " parameters but " + args.length + " nested args.");
        }

        String displayText;
        if (rawText.indexOf('\'') >= 0) {
            displayText = replaceSpecialChars(escapeTextWithAQuote(rawText));
            displayText = plugInArgs(displayText, args);

        } else {
            displayText = plugInArgs(replaceSpecialChars(rawText), args);
        }
        propertiesPW.println(name + "=" + propertyText);
    }

    /**
     * <p>
     * Convert a message handle into a SQLState, stripping off trailing
     * encodings as necessary.
     * </p>
     */
    private String getSQLState(String name) {
        if (name.length() <= 5) {
            return name;
        } else {
            return name.substring(0, 5);
        }
    }

    /**
     * <p>
     * Get all of the human-readable parameter names out of the message element.
     * </p>
     */
    private String[] getArgs(Element message) throws Exception {
        NodeList args = message.getElementsByTagName("arg");
        int argCount = args.getLength();
        String[] retval = new String[argCount];

        for (int i = 0; i < argCount; i++) {
            Element arg = (Element) args.item(i);

            retval[i] = squeezeText(arg);
        }

        return retval;
    }

    /**
     * <p>
     * Count the substitutable arguments in an internationalized message string.
     * These arguments have the form {n} where n is a number.
     * </p>
     */
    private int countParameters(String text) {
        int argCount = 0;
        int argIdx = 0;

        while (true) {
            argIdx = text.indexOf('{', argIdx);

            if (argIdx >= 0) {
                argCount++;
                argIdx++;
            } else {
                break;
            }
        }

        return argCount;
    }

    /**
     * <p>
     * Plug arg values into parameter slots in an internationalizable message string.
     * </p>
     */
    private String plugInArgs(String message, String[] rawArgs) {
        int count = rawArgs.length;
        String[] cookedArgs = new String[count];
        MessageFormat format = new MessageFormat(message);

        // add xml angle brackets around the args
        for (int i = 0; i < count; i++) {
            cookedArgs[i] = "<varname>&lt;" + rawArgs[i] + "&gt;</varname>";
            format.setFormatByArgumentIndex(i, null); // use plain string format
        }

        return format.format(cookedArgs);
    }

    /////////////////////////////////////////////////////////////////////////
    //
    //  GENERALLY USEFUL MINIONS
    //
    /////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Echo a message to the console.
     * </p>
     */
    private void echo(String text) {
        System.out.println(text);
    }

    /**
     * <p>
     * Flush and close file writers.
     * </p>
     */
    private void finishWriting(FileWriter fw, PrintWriter pw) throws IOException {
        if ((fw == null) || (pw == null)) {
            return;
        }

        pw.flush();
        fw.flush();

        pw.close();
        fw.close();
    }

    ////////////////////////////////////////////////////////
    //
    // XML MINIONS
    //
    ////////////////////////////////////////////////////////

    private Element getFirstChild(Element node, String childName) throws Exception {
        return (Element) node.getElementsByTagName(childName).item(0);
    }

    /**
     * <p>
     * Squeeze the text out of an Element.
     * </p>
     */
    private String squeezeText(Element node) throws Exception {
        Node textChild = node.getFirstChild();
        return textChild.getNodeValue();
    }

    /**
     * Replace a substring with some equivalent. For example, we would
     * like to replace "<" with "&lt;" in the error messages.
     * Add any substrings you would like to replace in the code below.
     * Be aware that the first paramter to the replaceAll() method is
     * interpreted as a regular expression.
     *
     * @param input A String that may contain substrings that we want to replace
     * @return Output String where substrings selected for replacement have been
     * replaced.
     * @see java.util.regex.Pattern
     */
    private static String replaceSpecialChars(String input) {
        String output = input.replaceAll("<", "&lt;");
        output = output.replaceAll(">", "&gt;");
        return output;
    }


    /**
     * <p>
     * Replace newlines with the escape sequence needed by properties files.
     * Also, replace single quotes with two single quotes.
     * </p>
     */
    private static String escapePropertiesText(String input) {
        String output = input.replaceAll("\n", "\\\\n");
        output = output.replaceAll("\'", "\'\'");
        return output;
    }

    /**
     * <p>
     * Replace single quotes with two single quotes.
     * Only needed when there are parameters with quotes.
     * </p>
     */
    private static String escapeTextWithAQuote(String input) {
        return input.replaceAll("\'", "\'\'");
    }

}

