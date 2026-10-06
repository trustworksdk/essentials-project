"""Unit tests for stack-lint helpers whose input shapes the fixtures cannot all carry.

    python3 -m unittest essentials-plugin/tests/stack-lint/test_stack_lint.py
"""

import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "stack_lint", Path(__file__).resolve().parents[2] / "scripts" / "stack-lint.py")
if _spec is None or _spec.loader is None:
    raise ImportError("cannot load scripts/stack-lint.py")
sl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sl)


class BaseUrlConsumed(unittest.TestCase):
    def test_constant(self):
        self.assertTrue(sl.base_url_consumed(
            "const BASE = import.meta.env.VITE_API_BASE_URL ?? ''\nfetch(`${BASE}${url}`)"))

    def test_arrow_function(self):
        self.assertTrue(sl.base_url_consumed(
            "const baseUrl = (): string => import.meta.env.VITE_API_BASE_URL ?? ''\n"
            "fetch(`${baseUrl()}${url}`, { ...options })"))

    def test_function_declaration_and_concatenation(self):
        self.assertTrue(sl.base_url_consumed(
            "function baseUrl() {\n  return import.meta.env.VITE_API_BASE_URL\n}\nfetch(baseUrl() + url)"))

    def test_inline(self):
        self.assertTrue(sl.base_url_consumed("fetch(`${import.meta.env.VITE_API_BASE_URL}${url}`)"))

    def test_read_but_unused(self):
        self.assertFalse(sl.base_url_consumed(
            "const BASE = import.meta.env.VITE_API_BASE_URL ?? ''\nfetch(url)"))

    def test_earlier_declaration_does_not_swallow_the_read(self):
        # Without semicolons, a lazy span from `const a` must not reach the env read and name `a`.
        self.assertFalse(sl.base_url_consumed(
            "const a = 1\nconst BASE = import.meta.env.VITE_API_BASE_URL\nfetch(`${a}${url}`)"))

    def test_read_only_in_a_comment(self):
        self.assertFalse(sl.base_url_consumed(
            "// const BASE = import.meta.env.VITE_API_BASE_URL\nconst BASE = ''\nfetch(`${BASE}${url}`)"))


class YamlKeys(unittest.TestCase):
    def test_nesting_flow_maps_and_lists(self):
        keys = {k: (v, line) for k, v, line, _ in sl.yaml_keys(
            "spring:\n"
            "  security: { enabled: false }\n"
            "  data:\n"
            "    mongodb:\n"
            "      uri: x  # trailing comment\n"
            "app:\n"
            "  cors:\n"
            "    allowed-origins:\n"
            "      - https://a.example.com\n"
            "---\n"
            "essentials.life-cycles.start-life-cycles: false\n")}
        self.assertEqual(keys["spring.security.enabled"], ("false", 2))
        self.assertEqual(keys["spring.data.mongodb.uri"], ("x", 5))
        self.assertIn("app.cors.allowed-origins", keys)
        self.assertEqual(keys["essentials.life-cycles.start-life-cycles"], ("false", 11))

    def test_mongo_moved_table_is_relaxed_binding_form(self):
        self.assertEqual(sl.MONGO_MOVED[sl.norm_key("spring.data.mongodb.uuid-representation")],
                         "spring.mongodb.representation.uuid")
        self.assertNotIn(sl.norm_key("spring.data.mongodb.auto-index-creation"), sl.MONGO_MOVED)


class StripComments(unittest.TestCase):
    def test_urls_in_strings_survive(self):
        out = sl.strip_comments('val u = "http://localhost" // gone\n/* x\n y */ val z = 1')
        self.assertIn('"http://localhost"', out)
        self.assertNotIn("gone", out)
        self.assertEqual(out.count("\n"), 2)


class Versions(unittest.TestCase):
    def test_below(self):
        self.assertTrue(sl.version_below("1.4.0", "1.5.0"))
        self.assertTrue(sl.version_below("1.4", "1.5"))
        self.assertFalse(sl.version_below("1.5.0", "1.5"))
        self.assertFalse(sl.version_below("1.5.0-RC1", "1.5.0"))
        self.assertFalse(sl.version_below("1.100.0", "1.5.0"))

    def test_not_a_version_is_unknown(self):
        self.assertIsNone(sl.version_below("DEV-SNAPSHOT", "1.5.0"))
        self.assertIsNone(sl.version_below("${essentials.version}", "1.5.0"))
        self.assertIsNone(sl.version_below(None, "1.5.0"))

    def test_pins_carry_the_kotlin_floor(self):
        pins = sl.read_pins(sl.PLUGIN / sl.PINS_REL)
        self.assertRegex(pins["kotlin.floor"], r"^\d+\.\d+$")
        self.assertFalse(sl.version_below(pins["kotlin.version"], pins["kotlin.floor"]))


class TypedEdge(unittest.TestCase):
    """The S4 typed-edge readers: which declared types only the Essentials converter can bind."""

    def decls(self, text, kotlin):
        return {n: (s, b) for n, s, b, _ in sl.edge_type_decls(sl.blank_strings(sl.strip_comments(text)), kotlin)}

    def test_java_string_routes(self):
        d = self.decls(
            "public class A extends CharSequenceType<A> { public A(CharSequence v) { super(v); } }\n"
            "public class B extends CharSequenceType<B> { public B(String v) { super(v); } }\n"
            "public class C extends LongType<C> { public static C of(String v) { return null; } }\n"
            "public final class D<T extends X> extends ShopCode<D> implements Identifier { D(String v) {} }\n"
            "public record R(String value) { }\n", False)
        self.assertEqual(d["A"], (["CharSequenceType"], False))
        self.assertEqual(d["B"], (["CharSequenceType"], True))
        self.assertEqual(d["C"], (["LongType"], True))
        self.assertEqual(d["D"], (["ShopCode", "Identifier"], False), "a package-private constructor is not public")
        self.assertNotIn("R", d, "a record is not a class declaration and binds through its constructor")

    def test_kotlin_shapes(self):
        d = self.decls(
            "class A(value: CharSequence) : CharSequenceType<A>(value)\n"
            "class B(value: String) : CharSequenceType<B>(value)\n"
            "class C private constructor(value: String) : CharSequenceType<C>(value)\n"
            "@JvmInline\nvalue class V(override val value: String) : StringValueType<V>\n", True)
        self.assertEqual(d["A"], (["CharSequenceType"], False))
        self.assertEqual(d["B"], (["CharSequenceType"], True))
        self.assertEqual(d["C"], (["CharSequenceType"], False))
        self.assertEqual(d["V"][0], ["StringValueType"])

    def test_params(self):
        java = sl.blank_strings(sl.strip_comments(
            'f(@PathVariable("id") final OrderId id, @RequestParam @Valid Optional<Code> c) {}\n'
            '// @PathVariable Hidden h\nString s = "@PathVariable Quoted q";'))
        self.assertEqual([(a, n, s) for a, n, _, s, _ in sl.edge_params(java, False)],
                         [("PathVariable", "id", "OrderId"), ("RequestParam", "c", "Code")])
        kotlin = sl.blank_strings(sl.strip_comments("fun f(@PathVariable id: OrderId, @RequestParam t: Ticket?) {}"))
        self.assertEqual([(n, w, s) for _, n, w, s, _ in sl.edge_params(kotlin, True)],
                         [("id", "OrderId", "OrderId"), ("t", "Ticket?", "Ticket")])


if __name__ == "__main__":
    unittest.main()
