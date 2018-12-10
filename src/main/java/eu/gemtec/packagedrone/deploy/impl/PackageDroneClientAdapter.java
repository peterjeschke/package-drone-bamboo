/*
 * Copyright (c) 2018 Veselin Markov. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.gemtec.packagedrone.deploy.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.equinox.internal.p2.core.helpers.SecureXMLUtil;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.task.TaskResultBuilder;

import eu.gemtec.packagedrone.deploy.impl.entity.ArtifactType;
import eu.gemtec.packagedrone.deploy.impl.entity.PackageDroneJarArtifact;
import eu.gemtec.packagedrone.deploy.impl.entity.PackageDroneJarArtifact.GAV;
import eu.gemtec.packagedrone.deploy.impl.entity.PackageDroneOsgiArtifact;
import eu.gemtec.packagedrone.deploy.impl.upload.UploadClient;

/**
 * @author Veselin Markov
 *
 */
public class PackageDroneClientAdapter {

	private final UploadClient pdClient;
	private final boolean skipUnparseableFiles;
	private final BuildLogger buildLogger;

	public PackageDroneClientAdapter(	String host,
										long port,
										String channel,
										String key,
										boolean uploadPoms,
										boolean skipUnparseableFiles,
										BuildLogger buildLogger) {
		this.skipUnparseableFiles = skipUnparseableFiles;
		this.buildLogger = buildLogger;
		pdClient = new UploadClient(key, channel, host + ":" + port, uploadPoms, buildLogger);
	}

	public TaskResult uploadFiles(Set<File> filesToUpload, TaskResultBuilder taskResultBuilder) {
		List<PackageDroneJarArtifact> features = new LinkedList<>();
		List<PackageDroneJarArtifact> bundles = new LinkedList<>();
		for (File file : filesToUpload) {
			buildLogger.addBuildLogEntry("Checking file: " + file.getAbsolutePath());
			try {
				GAV gav = getGav(file);
				if (gav == null) {
					if (skipUnparseableFiles) {
						buildLogger.addBuildLogEntry("File has no GAV, skipping: " + file.getAbsolutePath());
						continue;
					}
					buildLogger.addBuildLogEntry("File has no GAV, aborting: " + file.getAbsolutePath());
					throw new RuntimeException("File has no GAV: " + file.getAbsolutePath());
				}
				PackageDroneJarArtifact pdArtifact = UploadClient.makeArtifact(file, gav);

				ArtifactType artifactType = pdArtifact.getType();
				buildLogger.addBuildLogEntry("ArtifactType: " + artifactType);
				if (artifactType == ArtifactType.FEATURE)
					features.add(pdArtifact);
				else
					bundles.add(pdArtifact);
			} catch (IOException | ParserConfigurationException | SAXException | RuntimeException e) {
				buildLogger.addErrorLogEntry("Error while collecting artifacts", e);
				return taskResultBuilder.failedWithError().build();
			}
		}

		buildLogger.addBuildLogEntry("Uploading Features");
		for (PackageDroneJarArtifact pdArtifact : features) {
			buildLogger.addBuildLogEntry("Uploading Feature: " + pdArtifact.getGav().getMavenGroup() + ":" + pdArtifact.getGav().getMavenArtifact() + ":" + pdArtifact.getGav().getMavenVersion() + " via file: " + pdArtifact.getFile().getName());
			try {
				bundles.removeIf(pda -> pdClient.featureHasArtifact(pdArtifact, pda));
				pdClient.tryUploadFeature(pdArtifact, bundles, buildLogger);
			} catch (Exception e) {
				buildLogger.addErrorLogEntry("Error while uploading artifacts", e);
				return taskResultBuilder.failedWithError().build();
			}
		}

		buildLogger.addBuildLogEntry("Uploading Bundles without features");
		for (PackageDroneJarArtifact pdArtifact : bundles) {
			buildLogger.addBuildLogEntry("Uploading Bundle: " + pdArtifact.getGav().getMavenGroup() + ":" + pdArtifact.getGav().getMavenArtifact() + ":" + pdArtifact.getGav().getMavenVersion() + " via file: " + pdArtifact.getFile().getName());
			try {
				pdClient.tryUploadArtifact(pdArtifact, buildLogger);
			} catch (Exception e) {
				buildLogger.addErrorLogEntry("Error while uploading artifact", e);
				return taskResultBuilder.failedWithError().build();
			}
		}
		return taskResultBuilder.success().build();
	}

	@Nullable
	private GAV getGav(File file) throws ParserConfigurationException, SAXException, IOException {
		Document doc = findPom(file);

		if (doc == null)
			return null;

		Node project = doc.getElementsByTagName("project").item(0);
		Node groupId = getChildElementWithTagName(project, "groupId");
		Node artifactId = getChildElementWithTagName(project, "artifactId");
		Node version = getChildElementWithTagName(project, "version");
		Node parent = getChildElementWithTagName(project, "parent");

		if (groupId == null && parent != null) {
			groupId = getChildElementWithTagName(parent, "groupId");
		}
		if (version == null && parent != null) {
			version = getChildElementWithTagName(parent, "version");
		}

		if (groupId == null || artifactId == null || version == null) {
			throw new RuntimeException("POM didn't contain all necessary fields");
		}
		String versionString;
		if (version.getTextContent().contains("SNAPSHOT")) {
			versionString = file.getName().substring(file.getName().indexOf('-') + 1, file.getName().length() - 4);
		} else {
			versionString = version.getTextContent();
		}
		return new GAV(groupId.getTextContent(), artifactId.getTextContent(), versionString);
	}

	private Node getChildElementWithTagName(Node parent, String tagName) {
		for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
			Node item = parent.getChildNodes().item(i);
			if (tagName.equals(item.getNodeName())) {
				return item;
			}
		}
		return null;
	}

	/**
	 * Tries to find and parse the pom.xml file from the given file.
	 * 
	 * @param file
	 *                 a jar-file that contains a pom.xml
	 * @return the parsed document or {@code null}, if no pom is found
	 */
	private Document findPom(File file) throws ParserConfigurationException, SAXException, IOException, ZipException {
		try (ZipFile jar = new ZipFile(file)) {
			Optional<? extends ZipEntry> pomFile = jar.stream().filter(zEnty -> zEnty.getName().endsWith("pom.xml")).findAny();
			if (pomFile.isPresent()) {
				DocumentBuilderFactory newSecureXMLReader = SecureXMLUtil.newSecureDocumentBuilderFactory();
				newSecureXMLReader.setNamespaceAware(true);

				try (InputStream inputStream = jar.getInputStream(pomFile.get())) {
					DocumentBuilder newSAXParser = newSecureXMLReader.newDocumentBuilder();
					return newSAXParser.parse(inputStream);
				}
			}
		}
		return null;
	}
}
