package org.teslalinux.lambdatest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lambdas inside ART's libcore under ATL (run.sh: a plain dalvikvm with the installed boot class path).
 * libcore's own lambdas and method references are call sites of the boot jars: plain ones go through
 * LambdaMetafactory.metafactory, the serializable ones (Comparator.comparing, thenComparing,
 * Map.Entry.comparingByKey/Value, Comparator.naturalOrder().reversed() ...) through altMetafactory.
 * Prints PASS/FAIL per check and "RESULT passed/total".
 */
public class LambdaTest {
	static int passed, total;

	static void check(String name, java.util.concurrent.Callable<Boolean> c) {
		total++;
		String detail = null;
		boolean ok;
		try {
			ok = c.call();
		} catch (Throwable t) {
			ok = false;
			StringBuilder sb = new StringBuilder(t.toString());
			for (Throwable k = t.getCause(); k != null; k = k.getCause())
				sb.append(" <- ").append(k);
			detail = sb.toString();
		}
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (detail != null ? "  (" + detail + ")" : ""));
	}

	public static void main(String[] args) {
		check("Comparator.comparing (serializable lambda in libcore)", () -> {
			List<String> l = new ArrayList<>(Arrays.asList("ccc", "a", "bb"));
			l.sort(Comparator.comparing(String::length));
			return l.equals(Arrays.asList("a", "bb", "ccc"));
		});
		check("Comparator.comparingInt and thenComparing", () -> {
			List<String> l = new ArrayList<>(Arrays.asList("bb", "ab", "c"));
			l.sort(Comparator.comparingInt(String::length).thenComparing(Function.identity()));
			return l.equals(Arrays.asList("c", "ab", "bb"));
		});
		check("Map.Entry.comparingByKey / comparingByValue", () -> {
			Map<String, Integer> m = new HashMap<>();
			m.put("b", 1);
			m.put("a", 2);
			List<Map.Entry<String, Integer>> e = new ArrayList<>(m.entrySet());
			e.sort(Map.Entry.comparingByKey());
			boolean byKey = e.get(0).getKey().equals("a");
			e.sort(Map.Entry.comparingByValue());
			return byKey && e.get(0).getKey().equals("b");
		});
		check("Comparator.reverseOrder / reversed", () -> {
			List<Integer> l = new ArrayList<>(Arrays.asList(1, 3, 2));
			l.sort(Comparator.<Integer>naturalOrder().reversed());
			return l.equals(Arrays.asList(3, 2, 1));
		});
		check("Collections.sort with Comparator.nullsFirst", () -> {
			List<String> l = new ArrayList<>(Arrays.asList("b", null, "a"));
			Collections.sort(l, Comparator.nullsFirst(Comparator.naturalOrder()));
			return l.get(0) == null && l.get(1).equals("a");
		});
		check("streams with Collectors.groupingBy (libcore lambdas)", () -> {
			Map<Integer, List<String>> g = Arrays.asList("a", "bb", "cc").stream().collect(Collectors.groupingBy(String::length));
			return g.get(2).size() == 2;
		});
		check("Function.andThen / compose (default methods over lambdas)", () -> {
			Function<Integer, Integer> f = x -> x + 1;
			return f.andThen(x -> x * 2).compose((Integer x) -> x - 3).apply(5) == 6;
		});
		System.out.println("RESULT " + passed + "/" + total);
		System.exit(passed == total ? 0 : 1);
	}
}
