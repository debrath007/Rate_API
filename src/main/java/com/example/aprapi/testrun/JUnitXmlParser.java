package com.example.aprapi.testrun;

import com.example.aprapi.testrun.dto.TestCaseResult;
import com.example.aprapi.testrun.dto.TestSuiteResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Reads Gradle's JUnit XML output into the shape the UI renders. */
@Component
public class JUnitXmlParser {

	/**
	 * Parses every {@code TEST-*.xml} written at or after {@code notBefore}. The cutoff matters:
	 * Gradle leaves previous runs' XML in place, so a filtered run would otherwise report suites
	 * that did not actually execute this time as though they had just passed.
	 */
	public List<TestSuiteResult> parseResults(Path resultsDir, long notBefore) {
		if (!Files.isDirectory(resultsDir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(resultsDir)) {
			return files
					.filter(Files::isRegularFile)
					.filter(path -> {
						String name = path.getFileName().toString();
						return name.startsWith("TEST-") && name.endsWith(".xml");
					})
					.filter(path -> path.toFile().lastModified() >= notBefore)
					.map(this::parseSuite)
					.sorted(Comparator.comparing(TestSuiteResult::className))
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to list test results in " + resultsDir, e);
		}
	}

	private TestSuiteResult parseSuite(Path xmlFile) {
		Document document = parseDocument(xmlFile.toFile());
		Element suite = document.getDocumentElement();

		List<TestCaseResult> cases = new ArrayList<>();
		NodeList testcaseNodes = suite.getElementsByTagName("testcase");
		for (int i = 0; i < testcaseNodes.getLength(); i++) {
			cases.add(parseCase((Element) testcaseNodes.item(i)));
		}

		return new TestSuiteResult(
				suite.getAttribute("name"),
				intAttr(suite, "tests"),
				intAttr(suite, "failures") + intAttr(suite, "errors"),
				intAttr(suite, "skipped"),
				doubleAttr(suite, "time"),
				textOfFirstChild(suite, "system-out"),
				cases);
	}

	private TestCaseResult parseCase(Element testcase) {
		Element failure = firstChildElement(testcase, "failure");
		if (failure == null) {
			failure = firstChildElement(testcase, "error");
		}
		boolean skipped = firstChildElement(testcase, "skipped") != null;

		String status = failure != null ? "failed" : (skipped ? "skipped" : "passed");
		return new TestCaseResult(
				testcase.getAttribute("classname"),
				testcase.getAttribute("name"),
				status,
				doubleAttr(testcase, "time"),
				failure != null ? failure.getAttribute("message") : null,
				failure != null ? failure.getTextContent() : null);
	}

	private Document parseDocument(File file) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// These files are build output rather than user input, but a parser that resolves
			// external entities is never worth leaving enabled.
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);

			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(file);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new IllegalStateException("Failed to parse test result file: " + file, e);
		}
	}

	private Element firstChildElement(Element parent, String tagName) {
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
				return (Element) child;
			}
		}
		return null;
	}

	private String textOfFirstChild(Element parent, String tagName) {
		Element child = firstChildElement(parent, tagName);
		if (child == null) {
			return null;
		}
		String text = child.getTextContent();
		return (text == null || text.isBlank()) ? null : text;
	}

	private int intAttr(Element element, String name) {
		String value = element.getAttribute(name);
		return value.isBlank() ? 0 : Integer.parseInt(value);
	}

	private double doubleAttr(Element element, String name) {
		String value = element.getAttribute(name);
		return value.isBlank() ? 0.0 : Double.parseDouble(value);
	}
}
