/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import static org.eclipse.jdt.ls.core.internal.JsonMessageHelper.getParams;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.ClassFileUtil;
import org.eclipse.jdt.ls.core.internal.DependencyUtil;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.WorkspaceHelper;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.FindReplaceDocumentAdapter;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Fred Bricon
 */
public class HoverHandlerTest extends AbstractProjectsManagerBasedTest {

	private static String HOVER_TEMPLATE =
			"{\n" +
					"    \"id\": \"1\",\n" +
					"    \"method\": \"textDocument/hover\",\n" +
					"    \"params\": {\n" +
					"        \"textDocument\": {\n" +
					"            \"uri\": \"${file}\"\n" +
					"        },\n" +
					"        \"position\": {\n" +
					"            \"line\": ${line},\n" +
					"            \"character\": ${char}\n" +
					"        }\n" +
					"    },\n" +
					"    \"jsonrpc\": \"2.0\"\n" +
					"}";

	private HoverHandler handler;

	private IProject project;

	private IPackageFragmentRoot sourceFolder;

	private PreferenceManager preferenceManager;

	@Before
	public void setup() throws Exception {
		importProjects("eclipse/hello");
		project = WorkspaceHelper.getProject("hello");
		IJavaProject javaProject = JavaCore.create(project);
		sourceFolder = javaProject.getPackageFragmentRoot(javaProject.getProject().getFolder("src"));
		preferenceManager = mock(PreferenceManager.class);
		when(preferenceManager.getPreferences()).thenReturn(new Preferences());
		handler = new HoverHandler(preferenceManager);
	}

	@Test
	public void testHover() throws Exception {
		//given
		//Hovers on the System.out
		String payload = createHoverRequest("src/java/Foo.java", 5, 15);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);

		//then
		assertNotNull(hover);
		assertNotNull(hover.getContents());
		MarkedString signature = hover.getContents().get(0).getRight();
		assertEquals("Unexpected hover " + signature, "java", signature.getLanguage());
		assertEquals("Unexpected hover " + signature, "java.Foo", signature.getValue());
		String doc = hover.getContents().get(1).getLeft();
		assertEquals("Unexpected hover " + doc, "This is foo", doc);
	}

	@Test
	public void testHoverStandalone() throws Exception {
		//given
		//Hovers on the System.out
		URI standalone = Paths.get("projects", "maven", "salut", "src", "main", "java", "java", "Foo.java").toUri();
		String payload = createHoverRequest(standalone, 10, 71);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);

		//then
		assertNotNull(hover);
		assertNotNull(hover.getContents());
		MarkedString signature = hover.getContents().get(0).getRight();
		assertEquals("Unexpected hover " + signature, "java", signature.getLanguage());
		assertEquals("Unexpected hover " + signature, "java.Foo", signature.getValue());
		String doc = hover.getContents().get(1).getLeft();
		assertEquals("Unexpected hover " + doc, "This is foo", doc);
	}

	@Test
	public void testHoverPackage() throws Exception {
		// given
		// Hovers on the java.internal package
		String payload = createHoverRequest("src/java/Baz.java", 2, 16);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);

		// then
		assertNotNull(hover);
		String signature = hover.getContents().get(0).getRight().getValue();//
		assertEquals("Unexpected signature ", "java.internal", signature);
		String result = hover.getContents().get(1).getLeft();//
		assertEquals("Unexpected hover ", "this is a **bold** package!", result);
	}

	@Test
	public void testEmptyHover() throws Exception {
		//given
		//Hovers on the System.out
		URI standalone = Paths.get("projects", "maven", "salut", "src", "main", "java", "java", "Foo.java").toUri();
		String payload = createHoverRequest(standalone, 1, 2);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);

		//then
		assertNotNull(hover);
		assertNotNull(hover.getContents());
		assertEquals(1, hover.getContents().size());
		assertEquals("Should find empty hover for " + payload, "", hover.getContents().get(0).getLeft());
	}

	String createHoverRequest(String file, int line, int kar) {
		URI uri = project.getFile(file).getRawLocationURI();
		return createHoverRequest(uri, line, kar);
	}

	String createHoverRequest(ICompilationUnit cu, int line, int kar) {
		URI uri = cu.getResource().getRawLocationURI();
		return createHoverRequest(uri, line, kar);
	}

	String createHoverRequest(URI file, int line, int kar) {
		String fileURI = ResourceUtils.fixURI(file);
		return HOVER_TEMPLATE.replace("${file}", fileURI)
				.replace("${line}", String.valueOf(line))
				.replace("${char}", String.valueOf(kar));
	}

	@Test
	public void testHoverVariable() throws Exception {
		//given
		//Hover on args parameter
		String argParam = createHoverRequest("src/java/Foo.java", 7, 37);
		TextDocumentPositionParams position = getParams(argParam);

		//when
		Hover hover = handler.hover(position, monitor);

		//then
		assertNotNull(hover);
		assertNotNull(hover.getContents());
		MarkedString signature = hover.getContents().get(0).getRight();
		assertEquals("Unexpected hover " + signature, "java", signature.getLanguage());
		assertEquals("Unexpected hover " + signature, "String[] args - java.Foo.main(String[])", signature.getValue());
	}

	@Test
	public void testHoverMethod() throws Exception {
		IPackageFragment pack1 = sourceFolder.createPackageFragment("test1", false, null);
		StringBuilder buf = new StringBuilder();
		buf.append("package test1;\n");
		buf.append("import java.util.Vector;\n");
		buf.append("public class E {\n");
		buf.append("   public int foo(String s) { }\n");
		buf.append("   public static void foo2(String s, String s2) { }\n");
		buf.append("}\n");
		ICompilationUnit cu = pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		assertEquals("int test1.E.foo(String s)", getTitleHover(cu, 3, 15));
		assertEquals("void test1.E.foo2(String s, String s2)", getTitleHover(cu, 4, 24));
	}

	@Test
	public void testHoverTypeParameters() throws Exception {
		IPackageFragment pack1 = sourceFolder.createPackageFragment("test1", false, null);
		StringBuilder buf = new StringBuilder();
		buf.append("package test1;\n");
		buf.append("import java.util.Vector;\n");
		buf.append("public class E<T> {\n");
		buf.append("   public T foo(T s) { }\n");
		buf.append("   public <U> U bar(U s) { }\n");
		buf.append("}\n");
		ICompilationUnit cu = pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		assertEquals("T", getTitleHover(cu, 3, 10));
		assertEquals("T test1.E.foo(T s)", getTitleHover(cu, 3, 13));
		assertEquals("<U> U test1.E.bar(U s)", getTitleHover(cu, 4, 17));
	}

	@Test
	public void testHoverInheritedJavadoc() throws Exception {
		// given
		// Hovers on the overriding foo()
		String payload = createHoverRequest("src/java/Bar.java", 22, 19);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);

		// then
		assertNotNull(hover);
		String result = hover.getContents().get(1).getLeft();//
		assertEquals("Unexpected hover ", "This method comes from Foo", result);
	}

	@Test
	public void testHoverOverNullElement() throws Exception {
		IPackageFragment pack1 = sourceFolder.createPackageFragment("test1", false, null);
		StringBuilder buf = new StringBuilder();
		buf.append("package test1;\n");
		buf.append("import javax.xml.bind.Binder;\n");
		buf.append("public class E {}\n");
		ICompilationUnit cu = pack1.createCompilationUnit("E.java", buf.toString(), false, null);
		Hover hover = getHover(cu, 1, 8);
		assertNotNull(hover);
		assertEquals(1, hover.getContents().size());
		assertEquals("Unexpected hover ", "javax", hover.getContents().get(0).getRight().getValue());
	}

	@Test
	public void testHoverOnPackageWithJavadoc() throws Exception {
		importProjects("maven/salut2");
		project = WorkspaceHelper.getProject("salut2");
		handler = new HoverHandler(preferenceManager);
		//given
		//Hovers on the org.apache.commons import
		String payload = createHoverRequest("src/main/java/foo/Bar.java", 2, 22);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);
		assertNotNull(hover);
		String result = hover.getContents().get(0).getRight().getValue();//
		assertEquals("Unexpected hover ", "org.apache.commons", result);

		assertEquals(logListener.getErrors().toString(), 0, logListener.getErrors().size());
	}

	@Test
	public void testHoverThrowable() throws Exception {
		String uriString = ClassFileUtil.getURI(project, "java.lang.Exception");
		IClassFile classFile = JDTUtils.resolveClassFile(uriString);
		String contents = JavaLanguageServerPlugin.getContentProviderManager().getSource(classFile, monitor);
		IDocument document = new Document(contents);
		IRegion region = new FindReplaceDocumentAdapter(document).find(0, "Throwable", true, false, false, false);
		int offset = region.getOffset();
		int line = document.getLineOfOffset(offset);
		int character = offset - document.getLineOffset(line);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uriString);
		Position position = new Position(line, character);
		TextDocumentPositionParams params = new TextDocumentPositionParams(textDocument, position);
		Hover hover = handler.hover(params, monitor);
		assertNotNull(hover);
		assertTrue("Unexpected hover ", !hover.getContents().isEmpty());
	}

	@Test
	public void testHoverUnresolvedType() throws Exception {
		importProjects("eclipse/unresolvedtype");
		project = WorkspaceHelper.getProject("unresolvedtype");
		handler = new HoverHandler(preferenceManager);
		//given
		//Hovers on the IFoo
		String payload = createHoverRequest("src/pckg/Foo.java", 2, 31);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull(hover);
		assertTrue("Unexpected hover ", hover.getContents().isEmpty());
	}

	@Test
	public void testHoverWithAttachedJavadoc() throws Exception {
		File commonPrimitivesJdoc = DependencyUtil.getJavadoc("commons-primitives", "commons-primitives", "1.0");
		assertNotNull("Unable to locate  commons-primitives-1.0-javadoc.jar", commonPrimitivesJdoc);

		importProjects("maven/attached-javadoc");
		project = WorkspaceHelper.getProject("attached-javadoc");
		handler = new HoverHandler(preferenceManager);
		//given
		//Hovers on org.apache.commons.collections.primitives.ShortCollections which has no source but an attached javadoc
		String payload = createHoverRequest("src/main/java/org/sample/Bar.java", 2, 56);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);
		assertTrue("Unexpected hover :\n" + content, content.contains("This class consists exclusively of static methods that operate on or return ShortCollections"));
		assertTrue("Unexpected hover :\n" + content, content.contains("**Author:**"));
	}

	@Test
	public void testHoverOnJavadocWithValueTag() throws Exception {
		importProjects("maven/salut");
		project = WorkspaceHelper.getProject("salut");
		handler = new HoverHandler(preferenceManager);
		//given
		String payload = createHoverRequest("src/main/java/java/Foo2.java", 12, 30);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);
		assertMatches("\\[\"SimpleStringData\"\\]\\(file:/.*/salut/src/main/java/java/Foo2.java#13\\) is a simple String", content);
	}

	@Test
	public void testHoverOnJavadocWithLinkToMethodInClass() throws Exception {
		importProjects("maven/salut");
		project = WorkspaceHelper.getProject("salut");
		handler = new HoverHandler(preferenceManager);
		//given
		String payload = createHoverRequest("src/main/java/java/Foo2.java", 18, 25);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);
		assertMatches("\\[newMethodBeingLinkedToo\\]\\(file:/.*/salut/src/main/java/java/Foo2.java#23\\)", content);
	}

	@Test
	public void testHoverOnJavadocWithLinkToMethodInOtherClass() throws Exception {
		importProjects("maven/salut");
		project = WorkspaceHelper.getProject("salut");
		handler = new HoverHandler(preferenceManager);

		//given
		String payload = createHoverRequest("src/main/java/java/Foo2.java", 29, 25);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);
		assertMatches("\\[Foo.linkedFromFoo2\\(\\)\\]\\(file:/.*/salut/src/main/java/java/Foo.java#14\\)", content);
	}

	@Test
	public void testHoverOnJavadocWithMultipleDifferentTypesOfTags() throws Exception {
		importProjects("maven/salut");
		project = WorkspaceHelper.getProject("salut");
		handler = new HoverHandler(preferenceManager);
		//given
		String payload = createHoverRequest("src/main/java/java/Foo2.java", 44, 24);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);

		String expectedJavadoc = "This Javadoc contains a link to \\[newMethodBeingLinkedToo\\]\\(file:/.*/salut/src/main/java/java/Foo2.java#23\\)\n" + "\n" + " \\*  \\*\\*Parameters:\\*\\*\n" + "    \n"
				+ "     \\*  \\*\\*someString\\*\\* the string to enter\n" + " \\*  \\*\\*Returns:\\*\\*\n" + "    \n" + "     \\*  String\n" + " \\*  \\*\\*Throws:\\*\\*\n" + "    \n" + "     \\*  IOException\n"
				+ " \\*  \\*\\*Since:\\*\\*\n" + "    \n" + "     \\*  0.0.1\n" + " \\*  \\*\\*Version:\\*\\*\n" + "    \n" + "     \\*  0.0.1\n" + " \\*  \\*\\*Author:\\*\\*\n" + "    \n" + "     \\*  jpinkney\n"
				+ " \\*  \\*\\*See Also:\\*\\*\n" + "    \n" + "     \\*  \\[Online docs for java\\]\\(https://docs.oracle.com/javase/7/docs/api/\\)";
		assertMatches(expectedJavadoc, content);
	}

	@Test
	public void testHoverWhenLinkDoesNotExist() throws Exception {
		importProjects("maven/salut");
		project = WorkspaceHelper.getProject("salut");
		handler = new HoverHandler(preferenceManager);

		//given
		String payload = createHoverRequest("src/main/java/java/Foo2.java", 51, 25);
		TextDocumentPositionParams position = getParams(payload);

		// when
		Hover hover = handler.hover(position, monitor);
		assertNotNull("Hover is null", hover);
		assertEquals("Unexpected hover contents:\n" + hover.getContents(), 2, hover.getContents().size());
		Either<String, MarkedString> javadoc = hover.getContents().get(1);
		String content = null;
		assertTrue("javadoc has null content", javadoc != null && javadoc.getLeft() != null && (content = javadoc.getLeft()) != null);
		assertMatches("This link doesnt work LinkToSomethingNotFound", content);
	}

	@Test
	public void testHoverJavadocWithExtraTags() throws Exception {
		IPackageFragment pack1 = sourceFolder.createPackageFragment("test1", false, null);
		StringBuilder buf = new StringBuilder();
		//@formatter:off
		buf.append("package test1;\n");
		buf.append("/**\n" +
				" * Some text.\n" +
				" *\n" +
				" * @uses java.sql.Driver\n" +
				" *\n" +
				" * @moduleGraph\n" +
				" * @since 9\n" +
				" */\n");
		buf.append("public class Meh {}\n");
		//@formatter:on
		ICompilationUnit cu = pack1.createCompilationUnit("Meh.java", buf.toString(), false, null);
		Hover hover = getHover(cu, 9, 15);
		assertNotNull(hover);
		assertEquals(2, hover.getContents().size());

		//@formatter:off
		String expectedJavadoc = "Some text.\n" +
				"\n" +
				" *  **Since:**\n" +
				"    \n" +
				"     *  9\n" +
				" *  **@uses**\n" +
				"    \n" +
				"     *  java.sql.Driver\n" +
				" *  **@moduleGraph**";
		//@formatter:on
		assertEquals("Unexpected hover ", expectedJavadoc, hover.getContents().get(1).getLeft());
	}

	@Test
	public void testHoverOnPackageWithNewJavadoc() throws Exception {
		importProjects("eclipse/java9");
		project = WorkspaceHelper.getProject("java9");
		handler = new HoverHandler(preferenceManager);
		//given
		//Hovers on the java.sql import
		String payload = createHoverRequest("src/main/java/foo/bar/MyDriverAction.java", 2, 14);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);
		assertNotNull(hover);
		String javadoc = hover.getContents().get(1).getLeft();
		//Javadoc was read from https://docs.oracle.com/javase/9/docs/api/java/sql/package-summary.html
		assertTrue(javadoc.contains("JDBC™ API Tutorial and Reference, Third Edition"));
		assertFalse(javadoc.contains("----"));//no table nonsense

	}

	@Test
	public void testHoverOnJava10var() throws Exception {
		importProjects("eclipse/java10");
		project = WorkspaceHelper.getProject("java10");
		assertNoErrors(project);

		handler = new HoverHandler(preferenceManager);
		//given
		//Hovers on name.toUpperCase()
		String payload = createHoverRequest("src/main/java/foo/bar/Foo.java", 8, 34);
		TextDocumentPositionParams position = getParams(payload);

		//when
		Hover hover = handler.hover(position, monitor);
		assertNotNull(hover);
		assertNotNull(hover.toString(), hover.getContents().get(0).getRight());
		String javadoc = hover.getContents().get(0).getRight().getValue();
		assertEquals("String java.lang.String.toUpperCase()", javadoc);

	}

	private String getTitleHover(ICompilationUnit cu, int line, int character) {
		// when
		Hover hover = getHover(cu, line, character);

		// then
		assertNotNull(hover);
		MarkedString result = hover.getContents().get(0).getRight();
		return result.getValue();
	}

	private Hover getHover(ICompilationUnit cu, int line, int character) {
		String payload = createHoverRequest(cu, line, character);
		TextDocumentPositionParams position = getParams(payload);
		return handler.hover(position, monitor);
	}

}
