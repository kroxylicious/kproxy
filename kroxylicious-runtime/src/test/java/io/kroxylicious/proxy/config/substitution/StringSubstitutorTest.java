/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.substitution;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.kroxylicious.proxy.config.substitution.lookup.StringLookup;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for {@link StringSubstitutor}.
 */
class StringSubstitutorTest {

    private static final String ACTUAL_ANIMAL = "quick brown fox";
    private static final String ACTUAL_TARGET = "lazy dog";
    private static final String CLASSIC_RESULT = "The quick brown fox jumps over the lazy dog.";
    private static final String CLASSIC_TEMPLATE = "The ${animal} jumps over the ${target}.";
    private static final String EMPTY_EXPR = "${}";

    protected Map<String, String> values;

    private void assertEqualsCharSeq(final CharSequence expected, final CharSequence actual) {
        assertEquals(expected, actual, () -> String.format("expected.length()=%,d, actual.length()=%,d",
                getLength(expected), getLength(actual)));
    }

    private int getLength(CharSequence expected) {
        return expected == null ? 0 : expected.length();
    }

    protected void doNotReplace(final String replaceTemplate) throws IOException {
        doTestNoReplace(new StringSubstitutor(values), replaceTemplate);
    }

    protected void doReplace(final String expectedResult, final String replaceTemplate, final boolean substring)
            throws IOException {
        doTestReplace(new StringSubstitutor(values), expectedResult, replaceTemplate, substring);
    }

    protected void doTestNoReplace(final StringSubstitutor substitutor, final String replaceTemplate)
            throws IOException {
        if (replaceTemplate == null) {
            assertNull(replace(substitutor, (String) null));
            assertNull(substitutor.replace((String) null, 0, 100));
            assertNull(substitutor.replace((char[]) null));
            assertNull(substitutor.replace((char[]) null, 0, 100));
            assertNull(substitutor.replace((StringBuilder) null));
            assertNull(substitutor.replace((StringBuilder) null, 0, 100));
        }
        else {
            assertEquals(replaceTemplate, replace(substitutor, replaceTemplate));
        }
    }

    protected void doTestReplace(final StringSubstitutor sub, final String expectedResult, final String replaceTemplate,
                                 final boolean substring)
            throws IOException {
        final String expectedShortResult = substring ? expectedResult.substring(1, expectedResult.length() - 1)
                : expectedResult;

        // replace using String
        final String actual = replace(sub, replaceTemplate);
        assertEquals(expectedResult, actual,
                () -> String.format("Index of difference: %,d", indexOfDifference(expectedResult, actual)));
        if (substring) {
            assertEquals(expectedShortResult, sub.replace(replaceTemplate, 1, replaceTemplate.length() - 2));
        }

        // replace using char[]
        final char[] chars = replaceTemplate.toCharArray();
        assertEquals(expectedResult, sub.replace(chars));
        if (substring) {
            assertEquals(expectedShortResult, sub.replace(chars, 1, chars.length - 2));
        }

        // replace using StringBuilder
        StringBuilder builder = new StringBuilder(replaceTemplate);
        assertEquals(expectedResult, sub.replace(builder));
        if (substring) {
            assertEquals(expectedShortResult, sub.replace(builder, 1, builder.length() - 2));
        }
    }

    private int indexOfDifference(String expectedResult, String actual) {
        if (expectedResult == actual) {
            return -1;
        }
        if (expectedResult == null || actual == null) {
            return 0;
        }
        int i;
        for (i = 0; i < ((CharSequence) expectedResult).length() && i < ((CharSequence) actual).length(); ++i) {
            if (((CharSequence) expectedResult).charAt(i) != ((CharSequence) actual).charAt(i)) {
                break;
            }
        }
        if (i < ((CharSequence) actual).length() || i < ((CharSequence) expectedResult).length()) {
            return i;
        }
        return -1;
    }

    /**
     * For subclasses to override.
     *
     * @throws IOException Thrown by subclasses.
     */
    protected String replace(final StringSubstitutor stringSubstitutor, final String template) throws IOException {
        return stringSubstitutor.replace(template);
    }

    @BeforeEach
    public void setUp() throws Exception {
        values = new HashMap<>();
        // shortest key and value.
        values.put("a", "1");
        values.put("aa", "11");
        values.put("aaa", "111");
        values.put("b", "2");
        values.put("bb", "22");
        values.put("bbb", "222");
        values.put("a2b", "b");
        // normal key and value.
        values.put("animal", ACTUAL_ANIMAL);
        values.put("target", ACTUAL_TARGET);
    }

    @AfterEach
    public void tearDown() throws Exception {
        values = null;
    }

    @Test
    void constructorNullMap() {
        final Map<String, Object> parameters = null;
        final StringSubstitutor s = new StringSubstitutor(parameters, "prefix", "suffix");
        assertNull(s.getStringLookup().lookup("X"));
    }

    @Test
    void constructorStringSubstitutor() {
        final StringSubstitutor source = new StringSubstitutor();
        source.setDisableSubstitutionInValues(true);
        source.setEnableSubstitutionInVariables(true);
        source.setEnableUndefinedVariableException(true);
        source.setEscapeChar('e');
        source.setValueDelimiter('d');
        source.setVariablePrefix('p');
        source.setVariableResolver(key -> null);
        source.setVariableSuffix('s');
        //
        final StringSubstitutor target = new StringSubstitutor(source);
        //
        assertTrue(target.isDisableSubstitutionInValues());
        assertTrue(target.isEnableSubstitutionInVariables());
        assertTrue(target.isEnableUndefinedVariableException());
        assertEquals('e', target.getEscapeChar());
        assertTrue(target.getValueDelimiterMatcher().toString().endsWith("['d']"),
                target.getValueDelimiterMatcher().toString());
        assertTrue(target.getVariablePrefixMatcher().toString().endsWith("['p']"),
                target.getValueDelimiterMatcher().toString());
        assertTrue(target.getVariableSuffixMatcher().toString().endsWith("['s']"),
                target.getValueDelimiterMatcher().toString());
    }

    /**
     * Tests get set.
     */
    @Test
    void getSetEscape() {
        final StringSubstitutor sub = new StringSubstitutor();
        assertEquals('$', sub.getEscapeChar());
        sub.setEscapeChar('<');
        assertEquals('<', sub.getEscapeChar());
    }

    /**
     * Tests interpolation with weird boundary patterns.
     */
    @Test
    void replace_JiraText178_WeirdPatterns1() throws IOException {
        doNotReplace("$${");
        doNotReplace("$${a");
        doNotReplace("$$${");
        doNotReplace("$$${a");
        doNotReplace("$${${a");
        doNotReplace("${${a}"); // "${a" is not a registered variable name.
        doNotReplace("${$${a}");
    }

    /**
     * Tests interpolation with weird boundary patterns.
     */
    @Test
    void replace_JiraText178_WeirdPatterns2() throws IOException {
        doReplace("${1}", "$${${a}}", false);
    }

    /**
     * Tests interpolation with weird boundary patterns.
     */
    @Test
    @Disabled
    void replace_JiraText178_WeirdPatterns3() throws IOException {
        doReplace("${${a}", "$${${a}", false); // not "$${1" or "${1"
    }

    /**
     * Tests adjacent keys.
     */
    @Test
    void replaceAdjacentAtEnd() throws IOException {
        values.put("code", "GBP");
        values.put("amount", "12.50");
        final StringSubstitutor sub = new StringSubstitutor(values);
        assertEqualsCharSeq("Amount is GBP12.50", replace(sub, "Amount is ${code}${amount}"));
    }

    /**
     * Tests adjacent keys.
     */
    @Test
    void replaceAdjacentAtStart() throws IOException {
        values.put("code", "GBP");
        values.put("amount", "12.50");
        final StringSubstitutor sub = new StringSubstitutor(values);
        assertEqualsCharSeq("GBP12.50 charged", replace(sub, "${code}${amount} charged"));
    }

    /**
     * Tests key replace changing map after initialization (not recommended).
     */
    @Test
    void replaceChangedMap() throws IOException {
        final StringSubstitutor sub = new StringSubstitutor(values);
        // no map change
        final String template = CLASSIC_TEMPLATE;
        assertEqualsCharSeq(CLASSIC_RESULT, replace(sub, template));
        // map change
        values.put("target", "moon");
        assertEqualsCharSeq("The quick brown fox jumps over the moon.", replace(sub, template));
    }

    /**
     * Tests complex escaping.
     */
    @Test
    void replaceComplexEscaping() throws IOException {
        doReplace("${1}", "$${${a}}", false);
        doReplace("${11}", "$${${aa}}", false);
        doReplace("${111}", "$${${aaa}}", false);
        doReplace("${quick brown fox}", "$${${animal}}", false);
        doReplace("The ${quick brown fox} jumps over the lazy dog.", "The $${${animal}} jumps over the ${target}.",
                true);
        doReplace("${${a}}", "$${$${a}}", false);
        doReplace("${${aa}}", "$${$${aa}}", false);
        doReplace("${${aaa}}", "$${$${aaa}}", false);
        doReplace("${${animal}}", "$${$${animal}}", false);
        doReplace(".${${animal}}", ".$${$${animal}}", false);
        doReplace("${${animal}}.", "$${$${animal}}.", false);
        doReplace(".${${animal}}.", ".$${$${animal}}.", false);
        doReplace("The ${${animal}} jumps over the lazy dog.", "The $${$${animal}} jumps over the ${target}.", true);
        doReplace("The ${quick brown fox} jumps over the lazy dog. ${1234567890}.",
                "The $${${animal}} jumps over the ${target}. $${${undefined.number:-1234567890}}.", true);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKey() throws IOException {
        doReplace("The ${} jumps over the lazy dog.", "The ${} jumps over the ${target}.", true);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyExtraFirst() throws IOException {
        assertEqualsCharSeq("." + EMPTY_EXPR, replace(new StringSubstitutor(values), "." + EMPTY_EXPR));
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyExtraLast() throws IOException {
        assertEqualsCharSeq(EMPTY_EXPR + ".", replace(new StringSubstitutor(values), EMPTY_EXPR + "."));
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyOnly() throws IOException {
        assertEquals(EMPTY_EXPR, replace(new StringSubstitutor(values), EMPTY_EXPR));
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyShortest() throws IOException {
        doNotReplace(EMPTY_EXPR);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyWithDefault() throws IOException {
        doReplace("The animal jumps over the lazy dog.", "The ${:-animal} jumps over the ${target}.", true);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyWithDefaultOnly() throws IOException {
        doReplace("animal", "${:-animal}", false);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyWithDefaultOnlyEmpty() throws IOException {
        doReplace("", "${:-}", false);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceEmptyKeyWithDefaultOnlyShortest() throws IOException {
        doReplace("a", "${:-a}", false);
    }

    /**
     * Tests replace with null.
     */
    @Test
    void replaceEmptyString() throws IOException {
        doNotReplace("");
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceEscaping() throws IOException {
        doReplace("The ${animal} jumps over the lazy dog.", "The $${animal} jumps over the ${target}.", true);
        doReplace("${a}", "$${a}", false);
        doReplace("${a${a}}", "$${a$${a}}", false);
        doReplace("${a${a${a}}}", "$${a$${a$${a}}}", false);
    }

    /**
     * Tests replace with fail on undefined variable.
     */
    @Test
    void replaceFailOnUndefinedVariable() throws IOException {
        values.put("animal.1", "fox");
        values.put("animal.2", "mouse");
        values.put("species", "2");
        final StringSubstitutor sub = new StringSubstitutor(values);
        sub.setEnableUndefinedVariableException(true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> replace(sub, "The ${animal.${species}} jumps over the ${target}."))
                .withMessage("Cannot resolve variable 'animal.${species' (enableSubstitutionInVariables=false).");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> replace(sub, "The ${animal.${species:-1}} jumps over the ${target}."))
                .withMessage("Cannot resolve variable 'animal.${species:-1' (enableSubstitutionInVariables=false).");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> replace(sub, "The ${test:-statement} is a sample for missing ${unknown}."))
                .withMessage("Cannot resolve variable 'unknown' (enableSubstitutionInVariables=false).");

        // if default value is available, exception will not be thrown
        assertEqualsCharSeq("The statement is a sample for missing variable.",
                replace(sub, "The ${test:-statement} is a sample for missing ${unknown:-variable}."));

        assertEqualsCharSeq("The fox jumps over the lazy dog.",
                replace(sub, "The ${animal.1} jumps over the ${target}."));
    }

    /**
     * Tests whether replace with fail on undefined variable with substitution in variable names enabled.
     */
    @Test
    void replaceFailOnUndefinedVariableWithReplaceInVariable() throws IOException {
        values.put("animal.1", "fox");
        values.put("animal.2", "mouse");
        values.put("species", "2");
        values.put("statement.1", "2");
        values.put("recursive", "1");
        values.put("word", "variable");
        values.put("testok.2", "statement");
        final StringSubstitutor sub = new StringSubstitutor(values);
        sub.setEnableUndefinedVariableException(true);
        sub.setEnableSubstitutionInVariables(true);

        assertEqualsCharSeq("The mouse jumps over the lazy dog.",
                replace(sub, "The ${animal.${species}} jumps over the ${target}."));
        values.put("species", "1");
        assertEqualsCharSeq("The fox jumps over the lazy dog.",
                replace(sub, "The ${animal.${species}} jumps over the ${target}."));

        // exception is thrown here because variable with name test.1 is missing
        assertThatIllegalArgumentException()
                .isThrownBy(() -> replace(sub, "The ${test.${statement}} is a sample for missing ${word}."))
                .withMessage("Cannot resolve variable 'statement' (enableSubstitutionInVariables=true).");

        // exception is thrown here because variable with name test.2 is missing
        assertThatIllegalArgumentException()
                .isThrownBy(() -> replace(sub, "The ${test.${statement.${recursive}}} is a sample for missing ${word}."))
                .withMessage("Cannot resolve variable 'test.2' (enableSubstitutionInVariables=true).");

        assertEqualsCharSeq("statement", replace(sub, "${testok.${statement.${recursive}}}"));

        assertEqualsCharSeq("${testok.2}", replace(sub, "$${testok.${statement.${recursive}}}"));

        assertEqualsCharSeq("The statement is a sample for missing variable.",
                replace(sub, "The ${testok.${statement.${recursive}}} is a sample for missing ${word}."));
    }

    /**
     * Tests when no incomplete prefix.
     */
    @Test
    void replaceIncompletePrefix() throws IOException {
        doReplace("The {animal} jumps over the lazy dog.", "The {animal} jumps over the ${target}.", true);
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceKeyStartChars() throws IOException {
        final String substring = StringSubstitutor.DEFAULT_VAR_START + "a";
        assertEqualsCharSeq(substring, replace(new StringSubstitutor(values), substring));
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceKeyStartChars1Only() throws IOException {
        final String substring = StringSubstitutor.DEFAULT_VAR_START.substring(0, 1);
        assertEqualsCharSeq(substring, replace(new StringSubstitutor(values), substring));
    }

    /**
     * Tests when no variable name.
     */
    @Test
    void replaceKeyStartChars2Only() throws IOException {
        final String substring = StringSubstitutor.DEFAULT_VAR_START.substring(0, 2);
        assertEqualsCharSeq(substring, replace(new StringSubstitutor(values), substring));
    }

    /**
     * Tests when no prefix or suffix.
     */
    @Test
    void replaceNoPrefixNoSuffix() throws IOException {
        doReplace("The animal jumps over the lazy dog.", "The animal jumps over the ${target}.", true);
    }

    /**
     * Tests when suffix but no prefix.
     */
    @Test
    void replaceNoPrefixSuffix() throws IOException {
        doReplace("The animal} jumps over the lazy dog.", "The animal} jumps over the ${target}.", true);
    }

    /**
     * Tests replace with no variables.
     */
    @Test
    void replaceNoVariables() throws IOException {
        doNotReplace("The balloon arrived.");
    }

    /**
     * Tests replace with null.
     */
    @Test
    void replaceNull() throws IOException {
        doNotReplace(null);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replacePartialString_noReplace() {
        final StringSubstitutor sub = new StringSubstitutor();
        assertEqualsCharSeq("${animal} jumps", sub.replace(CLASSIC_TEMPLATE, 4, 15));
    }

    /**
     * Tests when prefix but no suffix.
     */
    @Test
    void replacePrefixNoSuffix() throws IOException {
        doReplace("The ${animal jumps over the ${target} lazy dog.", "The ${animal jumps over the ${target} ${target}.",
                true);
    }

    /**
     * Tests simple recursive replace.
     */
    @Test
    void replaceRecursive() throws IOException {
        values.put("animal", "${critter}");
        values.put("target", "${pet}");
        values.put("pet", "${petCharacteristic} dog");
        values.put("petCharacteristic", "lazy");
        values.put("critter", "${critterSpeed} ${critterColor} ${critterType}");
        values.put("critterSpeed", "quick");
        values.put("critterColor", "brown");
        values.put("critterType", "fox");
        doReplace(CLASSIC_RESULT, CLASSIC_TEMPLATE, true);

        values.put("pet", "${petCharacteristicUnknown:-lazy} dog");
        doReplace(CLASSIC_RESULT, CLASSIC_TEMPLATE, true);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceSimple() throws IOException {
        doReplace(CLASSIC_RESULT, CLASSIC_TEMPLATE, true);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceSimpleKeySize1() throws IOException {
        doReplace("1", "${a}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceSimpleKeySize2() throws IOException {
        doReplace("11", "${aa}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceSimpleKeySize3() throws IOException {
        doReplace("111", "${aaa}", false);
    }

    @Test
    void replaceTakingCharSequenceReturningNull() {
        final StringSubstitutor strSubstitutor = new StringSubstitutor((StringLookup) null);

        assertNull(strSubstitutor.replace((CharSequence) null));
        assertFalse(strSubstitutor.isPreserveEscapes());
        assertEquals('$', strSubstitutor.getEscapeChar());
    }

    @Test
    void replaceThrowsStringIndexOutOfBoundsException() {
        final StringSubstitutor sub = new StringSubstitutor();

        // replace(char[], int, int)
        final char[] emptyCharArray = {};
        // offset greater than array length
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                () -> sub.replace(emptyCharArray, 0, 1));
        // source != null && (offset > source.length || offset < 0)
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                () -> sub.replace(emptyCharArray, 1, 0));

        // replace(String, int, int)
        // offset greater than source length
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                () -> sub.replace("", 1, 1));
        // source != null && offset >= 0 && offset <= source.length() && (length > -offset + source.length() || length < 0)
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
                () -> sub.replace("", 0, 1));
    }

    /**
     * Tests replace creates output same as input.
     */
    @Test
    void replaceToIdentical() throws IOException {
        values.put("animal", "$${${thing}}");
        values.put("thing", "animal");
        doReplace("The ${animal} jumps.", "The ${animal} jumps.", true);
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownKey() throws IOException {
        doReplace("The ${person} jumps over the lazy dog.", "The ${person} jumps over the ${target}.", true);
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownKeyDefaultValue() throws IOException {
        doReplace("The ${person} jumps over the lazy dog. 1234567890.",
                "The ${person} jumps over the ${target}. ${undefined.number:-1234567890}.", true);
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownKeyOnly() throws IOException {
        final String expected = "${person}";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownKeyOnlyExtraFirst() throws IOException {
        final String expected = ".${person}";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownKeyOnlyExtraLast() throws IOException {
        final String expected = "${person}.";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownShortestKeyOnly() throws IOException {
        final String expected = "${U}";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownShortestKeyOnlyExtraFirst() throws IOException {
        final String expected = ".${U}";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests unknown key replace.
     */
    @Test
    void replaceUnknownShortestKeyOnlyExtraLast() throws IOException {
        final String expected = "${U}.";
        assertEqualsCharSeq(expected, replace(new StringSubstitutor(values), expected));
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceVariablesCount1() throws IOException {
        doReplace(ACTUAL_ANIMAL, "${animal}", false);
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceVariablesCount1Escaping2To1() throws IOException {
        doReplace("${a}", "$${a}", false);
        doReplace("${animal}", "$${animal}", false);
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceVariablesCount1Escaping3To2() throws IOException {
        doReplace("$${a}", "$$${a}", false);
        doReplace("$${animal}", "$$${animal}", false);
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceVariablesCount1Escaping4To3() throws IOException {
        doReplace("$$${a}", "$$$${a}", false);
        doReplace("$$${animal}", "$$$${animal}", false);
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceVariablesCount1Escaping5To4() throws IOException {
        doReplace("$$$${a}", "$$$$${a}", false);
        doReplace("$$$${animal}", "$$$$${animal}", false);
    }

    /**
     * Tests escaping.
     */
    @Test
    void replaceVariablesCount1Escaping6To4() throws IOException {
        doReplace("$$$$${a}", "$$$$$${a}", false);
        doReplace("$$$$${animal}", "$$$$$${animal}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceVariablesCount2() throws IOException {
        // doTestReplace("12", "${a}${b}", false);
        doReplace("1122", "${aa}${bb}", false);
        doReplace(ACTUAL_ANIMAL + ACTUAL_ANIMAL, "${animal}${animal}", false);
        doReplace(ACTUAL_TARGET + ACTUAL_TARGET, "${target}${target}", false);
        doReplace(ACTUAL_ANIMAL + ACTUAL_TARGET, "${animal}${target}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceVariablesCount2NonAdjacent() throws IOException {
        doReplace("1 2", "${a} ${b}", false);
        doReplace("11 22", "${aa} ${bb}", false);
        doReplace(ACTUAL_ANIMAL + " " + ACTUAL_ANIMAL, "${animal} ${animal}", false);
        doReplace(ACTUAL_ANIMAL + " " + ACTUAL_ANIMAL, "${animal} ${animal}", false);
        doReplace(ACTUAL_ANIMAL + " " + ACTUAL_ANIMAL, "${animal} ${animal}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceVariablesCount3() throws IOException {
        doReplace("121", "${a}${b}${a}", false);
        doReplace("112211", "${aa}${bb}${aa}", false);
        doReplace(ACTUAL_ANIMAL + ACTUAL_ANIMAL + ACTUAL_ANIMAL, "${animal}${animal}${animal}", false);
        doReplace(ACTUAL_TARGET + ACTUAL_TARGET + ACTUAL_TARGET, "${target}${target}${target}", false);
    }

    /**
     * Tests simple key replace.
     */
    @Test
    void replaceVariablesCount3NonAdjacent() throws IOException {
        doReplace("1 2 1", "${a} ${b} ${a}", false);
        doReplace("11 22 11", "${aa} ${bb} ${aa}", false);
        doReplace(ACTUAL_ANIMAL + " " + ACTUAL_ANIMAL + " " + ACTUAL_ANIMAL, "${animal} ${animal} ${animal}", false);
        doReplace(ACTUAL_TARGET + " " + ACTUAL_TARGET + " " + ACTUAL_TARGET, "${target} ${target} ${target}", false);
    }

    /**
     * Tests interpolation with weird boundary patterns.
     */
    @Test
    void replaceWeirdPattens() throws IOException {
        doNotReplace("");
        doNotReplace(EMPTY_EXPR);
        doNotReplace("${ }");
        doNotReplace("${\t}");
        doNotReplace("${\n}");
        doNotReplace("${\b}");
        doNotReplace("${");
        doNotReplace("$}");
        doNotReplace("$$}");
        doNotReplace("}");
        doNotReplace("${}$");
        doNotReplace("${}$$");
        doNotReplace("${${");
        doNotReplace("${${}}");
        doNotReplace("${$${}}");
        doNotReplace("${$$${}}");
        doNotReplace("${$$${$}}");
        doNotReplace("${${}}");
        doNotReplace("${${ }}");
        //
        doNotReplace("${$${a}}");
        doNotReplace("${$$${a}}");
        doNotReplace("${${a}}");
        doNotReplace("${${${a}");
        doNotReplace("${ ${a}");
        doNotReplace("${ ${ ${a}");
        //
        doReplace("${1}", "$${${a}}", false);
        doReplace("${ 1}", "$${ ${a}}", false);
        doReplace("${12}", "$${${a}${b}}", false);
        doReplace("${ 1 2 }", "$${ ${a} ${b} }", false);
        doReplace("${${${a}2", "${${${a}${b}", false);
    }

    /**
     * Tests protected.
     */
    @Test
    void resolveVariable() {
        var builder = new StringBuilder("Hi ${name}!");
        final Map<String, String> map = new HashMap<>();
        map.put("name", "commons");
        final StringSubstitutor sub = new StringSubstitutor(map) {
            @Override
            protected String resolveVariable(final String variableName, final StringBuilder buf, final int startPos,
                                             final int endPos) {
                assertEquals("name", variableName);
                assertSame(builder, buf);
                assertEquals(3, startPos);
                assertEquals(10, endPos);
                return "jakarta";
            }
        };
    }

    /**
     * Tests interpolation with system properties.
     */

    @Test
    void substitutePreserveEscape() throws IOException {
        final String org = "${not-escaped} $${escaped}";
        final Map<String, String> map = new HashMap<>();
        map.put("not-escaped", "value");

        final StringSubstitutor sub = new StringSubstitutor(map, "${", "}", '$');
        assertFalse(sub.isPreserveEscapes());
        assertEqualsCharSeq("value ${escaped}", replace(sub, org));

        sub.setPreserveEscapes(true);
        assertTrue(sub.isPreserveEscapes());
        assertEqualsCharSeq("value $${escaped}", replace(sub, org));
    }

}
