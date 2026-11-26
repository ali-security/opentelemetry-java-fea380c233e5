/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.trace.propagation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import io.opentelemetry.api.trace.TraceState;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class W3CTraceContextEncodingTest {

  @ParameterizedTest
  @MethodSource("orderedMembers")
  void decodePreservesOrderAndIgnoresOptionalWhitespace(String header) {
    assertThat(
            W3CTraceContextEncoding.encodeTraceState(
                W3CTraceContextEncoding.decodeTraceState(header)))
        .isEqualTo("a=b,c=d");
  }

  private static Stream<Arguments> orderedMembers() {
    return Stream.of(
        Arguments.of(named("members without whitespace", "a=b,c=d")),
        Arguments.of(named("optional spaces and tabs around members", " \ta=b \t, \tc=d \t")),
        Arguments.of(
            named("leading, intervening, and trailing empty members", ",,a=b, \t,c=d,,")));
  }

  @ParameterizedTest
  @MethodSource("emptyMembers")
  void decodeEmptyMembers(String header) {
    assertThat(W3CTraceContextEncoding.decodeTraceState(header).isEmpty()).isTrue();
  }

  private static Stream<Arguments> emptyMembers() {
    return Stream.of(
        Arguments.of(named("empty header", "")),
        Arguments.of(named("whitespace-only header", " \t")),
        Arguments.of(named("empty members without whitespace", ",,")),
        Arguments.of(named("empty members with spaces and tabs", " ,\t, ")));
  }

  @Test
  void decodePreservesLeadingSpacesInValue() {
    assertThat(W3CTraceContextEncoding.decodeTraceState(" \ta=  b \t").get("a")).isEqualTo("  b");
  }

  @ParameterizedTest
  @MethodSource("invalidMembers")
  void decodeInvalidMembers(String header) {
    assertThat(W3CTraceContextEncoding.decodeTraceState(header).isEmpty()).isTrue();
  }

  private static Stream<Arguments> invalidMembers() {
    return Stream.of(
        Arguments.of(named("duplicate key", "a=b,a=c")),
        Arguments.of(named("empty value", "a=")),
        Arguments.of(named("empty key", "=b")),
        Arguments.of(named("uppercase key", "A=b")),
        Arguments.of(named("equals sign in value", "a=b=c")),
        Arguments.of(named("tab in value", "a=b\tx")),
        Arguments.of(named("newline in value", "a=b\nx")));
  }

  @Test
  void decodeMissingKeyValueDelimiter() {
    assertThatThrownBy(() -> W3CTraceContextEncoding.decodeTraceState("a=b,invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodeMemberCountLimit() {
    String header =
        IntStream.range(0, 32).mapToObj(i -> "key" + i + "=value").collect(Collectors.joining(","));
    assertThat(W3CTraceContextEncoding.decodeTraceState(header).size()).isEqualTo(32);
    assertThat(
            W3CTraceContextEncoding.decodeTraceState(",, " + header.replace(",", ", ,") + ",\t,"))
        .isEqualTo(W3CTraceContextEncoding.decodeTraceState(header));
    assertThatThrownBy(() -> W3CTraceContextEncoding.decodeTraceState(header + ",extra=value"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TraceState has too many elements.");
  }

  @Test
  void decodeDoesNotLimitCombinedLengthTo512() {
    String key = repeat('a', 256);
    String value = repeat('b', 256);
    TraceState state = W3CTraceContextEncoding.decodeTraceState(key + "=" + value + ",c=d");
    assertThat(state.size()).isEqualTo(2);
    assertThat(state.get(key)).isEqualTo(value);
  }

  @ParameterizedTest
  @MethodSource("whitespaceCharacters")
  @Timeout(5)
  void decodeLargeWhitespaceRuns(char whitespace) {
    String padding = repeat(whitespace, 100_000);
    TraceState expected = TraceState.builder().put("a", "b").build();
    assertThat(W3CTraceContextEncoding.decodeTraceState("a=b" + padding + "x").isEmpty()).isTrue();
    assertThat(W3CTraceContextEncoding.decodeTraceState("a=b" + padding)).isEqualTo(expected);
    assertThat(W3CTraceContextEncoding.decodeTraceState(padding + "a=b" + padding))
        .isEqualTo(expected);
    assertThat(W3CTraceContextEncoding.decodeTraceState("a=b," + padding)).isEqualTo(expected);
    assertThat(W3CTraceContextEncoding.decodeTraceState(padding).isEmpty()).isTrue();
  }

  private static Stream<Arguments> whitespaceCharacters() {
    return Stream.of(
        Arguments.of(named("long runs of spaces", ' ')),
        Arguments.of(named("long runs of tabs", '\t')));
  }

  @Test
  @Timeout(5)
  void decodeManyEmptyMembers() {
    assertThat(W3CTraceContextEncoding.decodeTraceState(repeat(',', 100_000) + "a=b"))
        .isEqualTo(TraceState.builder().put("a", "b").build());
  }

  private static String repeat(char character, int count) {
    char[] characters = new char[count];
    Arrays.fill(characters, character);
    return new String(characters);
  }
}
