package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.http.client.fluent.Request;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class GeonamesautoannotatorStepPlugin implements IStepPluginVersion2 {
    private static ObjectMapper mapper = new ObjectMapper();
    private static XPathFactory xFactory = XPathFactory.instance();
    private static Namespace altoNs = Namespace.getNamespace("alto", "http://www.loc.gov/standards/alto/ns-v2#");
    private static XPathExpression<Element> tagXpath = xFactory.compile("//alto:NamedEntityTag", Filters.element(), null, altoNs);
    XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());

    @Getter
    private String title = "intranda_step_geonamesautoannotator";
    @Getter
    private Step step;
    private String geonamesAccount;
    private String geonamesApiUrl;
    @Getter
    private boolean allowTaskFinishButtons;
    private String returnPath;
    private Map<String, String> geonamesCache;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        geonamesAccount = myconfig.getString("geonamesAccount", "testuser");
        geonamesApiUrl = myconfig.getString("geonamesApiUrl", "http://api.geonames.org");
        geonamesCache = new HashMap<>();
        log.info("Geonamesautoannotator step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        // return PluginGuiType.PART;
        // return PluginGuiType.PART_AND_FULL;
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_geonamesautoannotator.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;

        try {
            Path altoFolder = Paths.get(step.getProzess().getOcrAltoDirectory());
            enrichGeonames(altoFolder);
        } catch (SwapException | IOException | InterruptedException | JDOMException | URISyntaxException e) {
            log.error(e);
            successful = false;
        }

        log.info("Geonamesautoannotator step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    private void enrichGeonames(Path p) throws JDOMException, IOException, URISyntaxException, InterruptedException {
        SAXBuilder sax = new SAXBuilder();
        Document doc = sax.build(p.toFile());
        List<Element> tags = tagXpath.evaluate(doc);
        for (Element tag : tags) {
            if ("LOCATION".equals(tag.getAttributeValue("TYPE"))) {
                int geonameId = requestGeonames(tag.getAttributeValue("LABEL"), geonamesAccount);
                if (geonameId >= 0) {
                    String geonameURI = String.format("https://www.geonames.org/%d", geonameId);
                    tag.setAttribute("URI", geonameURI);
                }
            }
        }
        try (OutputStream out = Files.newOutputStream(p, StandardOpenOption.TRUNCATE_EXISTING)) {
            xmlOut.output(doc, out);
        }
    }

    private int requestGeonames(String searchTerm, String geonamesUser)
            throws URISyntaxException, IOException, InterruptedException {
        JsonNode results = requestGeonamesJson(searchTerm, geonamesUser);
        if (results != null && results.isArray() && results.size() > 0) {
            return results.get(0).get("geonameId").asInt();
        } else {
            results = requestGeonamesJson(searchTerm.substring(0, searchTerm.length() - 1), geonamesUser);
            if (results != null && results.isArray() && results.size() > 0) {
                return results.get(0).get("geonameId").asInt();
            }
        }
        return -1;
    }

    private JsonNode requestGeonamesJson(String searchTerm, String geonamesUser)
            throws URISyntaxException, IOException, InterruptedException {
        String url = String.format("%s/searchJSON?q=%s&maxRows=1&username=%s", geonamesApiUrl,
                URLEncoder.encode(searchTerm, "UTF-8"), geonamesUser);

        String body = geonamesCache.get(searchTerm);

        if (body == null) {
            body = Request.Get(url)
                    .execute()
                    .returnContent()
                    .asString();
            geonamesCache.put(searchTerm, body);
        }

        JsonNode root = mapper.readTree(body);
        JsonNode results = root.get("geonames");
        return results;
    }
}
