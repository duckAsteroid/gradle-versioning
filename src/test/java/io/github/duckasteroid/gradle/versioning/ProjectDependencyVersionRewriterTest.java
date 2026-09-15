package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Unit test for {@link ProjectDependencyVersionRewriter} against plain parsed POM XML. */
class ProjectDependencyVersionRewriterTest {

  @Test
  void rewritesOnlyDependenciesMatchingAKnownGav() throws Exception {
    Element pom = parse("""
        <project>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>dorsair-client</artifactId>
              <version>0.1.1-cool-branch-SNAPSHOT</version>
            </dependency>
            <dependency>
              <groupId>com.other</groupId>
              <artifactId>unrelated</artifactId>
              <version>2.0.0</version>
            </dependency>
          </dependencies>
        </project>
        """);

    ProjectDependencyVersionRewriter.rewrite(pom, Map.of("com.example:dorsair-client", "0.1.1"));

    String xml = serialize(pom);
    assertEquals(false, xml.contains("0.1.1-cool-branch-SNAPSHOT"), "matched dependency's version should be rewritten: " + xml);
    assertEquals(true, xml.contains("<version>0.1.1</version>"), "matched dependency should get the final release version: " + xml);
    assertEquals(true, xml.contains("<version>2.0.0</version>"), "unrelated dependency should be untouched: " + xml);
  }

  @Test
  void leavesDependencyManagementAlone() throws Exception {
    Element pom = parse("""
        <project>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>dorsair-client</artifactId>
                <version>0.1.1-cool-branch-SNAPSHOT</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """);

    ProjectDependencyVersionRewriter.rewrite(pom, Map.of("com.example:dorsair-client", "0.1.1"));

    assertEquals(true, serialize(pom).contains("0.1.1-cool-branch-SNAPSHOT"),
        "dependencyManagement's nested <dependencies> must not be touched by the top-level scan");
  }

  @Test
  void noopWhenMapIsEmpty() throws Exception {
    Element pom = parse("""
        <project>
          <dependencies>
            <dependency>
              <groupId>com.example</groupId>
              <artifactId>dorsair-client</artifactId>
              <version>0.1.1-cool-branch-SNAPSHOT</version>
            </dependency>
          </dependencies>
        </project>
        """);

    ProjectDependencyVersionRewriter.rewrite(pom, Map.of());

    assertEquals(true, serialize(pom).contains("0.1.1-cool-branch-SNAPSHOT"));
  }

  private static Element parse(String xml) throws Exception {
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    return doc.getDocumentElement();
  }

  private static String serialize(Element element) throws Exception {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(element.getOwnerDocument()), new StreamResult(out));
    return out.toString(StandardCharsets.UTF_8);
  }
}
