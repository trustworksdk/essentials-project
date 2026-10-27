"""Unit tests for stack-lint helpers whose input shapes the fixtures cannot all carry.

    python3 -m unittest essentials-plugin/tests/stack-lint/test_stack_lint.py
"""

import importlib.util
import shutil
import tempfile
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

    def test_next_process_env(self):
        self.assertTrue(sl.base_url_consumed(
            "const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? ''\nfetch(apiBase + url, options)"))

    def test_cra_bracket_form_inline(self):
        self.assertTrue(sl.base_url_consumed("fetch(`${process.env['REACT_APP_API_BASE_URL'] ?? ''}${url}`)"))

    def test_any_variable_name(self):
        self.assertTrue(sl.base_url_consumed(
            "function apiRoot() { return import.meta.env.VITE_BACKEND ?? '' }\nfetch(`${apiRoot()}${url}`)"))

    def test_process_env_read_but_unused(self):
        self.assertFalse(sl.base_url_consumed(
            "const apiBase = process.env['NEXT_PUBLIC_API_BASE_URL'] ?? ''\nconsole.log(apiBase)\nfetch(url)"))

    def test_process_env_alone_is_not_a_read(self):
        self.assertFalse(sl.base_url_consumed("const env = process.env\nfetch(`${env}${url}`)"))


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


class ConfigShapes(unittest.TestCase):
    """Whether a key is a list, as Spring binds it: the half of S8's @Value check that config_keys cannot see."""

    def shapes(self, name, text):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / name
            f.write_text(text, encoding="utf-8")
            return sl.config_shapes(f)

    def test_yaml_block_flow_and_scalar(self):
        got = self.shapes("application.yml",
                          "app:\n"
                          "  cors:\n"
                          "    allowed-origins:\n"
                          "      - https://a.example.com   # a comment\n"
                          "    compact:\n"
                          "    - https://b.example.com\n"
                          "    flow: [https://c.example.com, https://d.example.com]\n"
                          "    scalar: https://e.example.com,https://f.example.com\n"
                          "    nested:\n"
                          "      key: value\n")
        self.assertEqual(got[sl.norm_key("app.cors.allowed-origins")], ("sequence", 3))
        self.assertEqual(got[sl.norm_key("app.cors.compact")], ("sequence", 5))
        self.assertEqual(got[sl.norm_key("app.cors.flow")], ("sequence", 7))
        self.assertEqual(got[sl.norm_key("app.cors.scalar")], ("scalar", 8))
        self.assertEqual(got[sl.norm_key("app.cors.nested")], ("mapping", 9))
        self.assertEqual(got[sl.norm_key("app.cors")][0], "mapping")

    def test_a_sibling_item_is_not_the_keys_sequence(self):
        got = self.shapes("application.yml", "list:\n  - name: a\n    origins:\n  - name: b\n")
        self.assertEqual(got[sl.norm_key("list")][0], "sequence")
        self.assertEqual(got[sl.norm_key("list.origins")][0], "mapping",
                         "a `- ` shallower than the key starts the parent's next item")

    def test_properties_indexed_and_relaxed_binding(self):
        got = self.shapes("application.properties",
                          "app.cors.allowedOrigins[0]=https://a.example.com\n"
                          "app.cors.allowed-origins[1]=https://b.example.com\n"
                          "app.cors.exposed=https://c.example.com\n")
        self.assertEqual(got[sl.norm_key("app.cors.allowed-origins")], ("sequence", 1))
        self.assertEqual(got[sl.norm_key("app.cors.exposed")], ("scalar", 3))


class OrvalConfig(unittest.TestCase):
    def test_template_render(self):
        got = sl.orval_config((sl.PLUGIN / "references/init-assets/project/frontend/orval.config.ts.template")
                              .read_text(encoding="utf-8"))
        self.assertTrue(got["readable"])
        self.assertEqual([v for v, _ in got["targets"]], ["./src/shared/api/generated"])
        self.assertEqual([v for v, _ in got["schemas"]], ["./src/shared/api/model"])
        self.assertEqual([v for v, _ in got["mutators"]], ["./src/shared/api/custom-fetch.ts"])
        self.assertEqual([v for v, _ in got["inputs"]], ["../contracts/openapi.json"])

    def test_string_forms_and_several_projects(self):
        got = sl.orval_config("export default {\n a: { input: './a.json', output: './src/a.ts' },\n"
                              " b: { output: { target: 'g', schemas: { path: 'm', type: 'zod' },\n"
                              "      override: { mutator: './lib/m.ts' } } } }")
        self.assertTrue(got["readable"])
        self.assertEqual(got["targets"], [("./src/a.ts", 2), ("g", 3)])
        self.assertEqual(got["schemas"], [("m", 3)])
        self.assertEqual(got["mutators"], [("./lib/m.ts", 4)])

    def test_nested_target_is_not_the_outputs(self):
        got = sl.orval_config("export default { api: { output: { override: { query: { target: 'x' } }, "
                              "target: './gen' } } }")
        self.assertEqual([v for v, _ in got["targets"]], ["./gen"])

    def test_computed_is_unreadable_but_keeps_inputs(self):
        for text in ("export default { api: { input: '../c.json', output: { target: path.join(dir, 'gen') } } }",
                     "export default { api: { input: '../c.json', output: { target: 'g', schemas: SCHEMAS } } }",
                     "export default { api: { input: '../c.json', output: { target: 'g', "
                     "override: { mutator: { path: MUTATOR } } } } }",
                     "import config from './shared'\nexport default { api: { input: '../c.json', output: config } }"):
            got = sl.orval_config(text)
            self.assertFalse(got["readable"], text)
            self.assertEqual([v for v, _ in got["inputs"]], ["../c.json"], text)

    def test_comments_are_not_config(self):
        got = sl.orval_config("// output: { target: './old' }\nexport default { api: { output: { target: './new' } } }")
        self.assertEqual([v for v, _ in got["targets"]], ["./new"])


class GitignoreCovers(unittest.TestCase):
    def test_shapes_git_accepts(self):
        rel = "src/shared/api/generated"
        for pattern in ("/src/shared/api/generated", "src/shared/api/generated/", "generated/", "generated",
                        "/src/shared/api/", "src/*/api/generated", "**/api/generated", "gen*"):
            self.assertTrue(sl.gitignore_covers(rel, [pattern]), pattern)

    def test_shapes_that_miss(self):
        rel = "src/shared/api/generated"
        for pattern in ("/generated", "api/generated", "# src/shared/api/generated", "!src/shared/api/generated",
                        "src/shared/api/model/", "", "generated.ts"):
            self.assertFalse(sl.gitignore_covers(rel, [pattern]), pattern)

    def test_relative_to_the_gitignores_directory(self):
        self.assertTrue(sl.gitignore_covers("frontend/src/shared/api/model", ["frontend/src/shared/api/model/"]))
        self.assertTrue(sl.gitignore_covers("frontend/lib/api/model", ["/frontend/lib/api/"]))


class FrontendTooling(unittest.TestCase):
    def tooling(self, package_json, *files):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "package.json").write_text(package_json, encoding="utf-8")
            for f in files:
                (d / f).write_text("", encoding="utf-8")
            return sl.frontend_tooling(d)

    def test_detection(self):
        self.assertEqual(self.tooling('{"devDependencies": {"vite": "7"}}'), "vite")
        self.assertEqual(self.tooling('{}', "vite.config.ts"), "vite")
        self.assertEqual(self.tooling('{"dependencies": {"next": "16"}, "devDependencies": {"vite": "7"}}'), "next")
        self.assertEqual(self.tooling('{}', "next.config.mjs"), "next")
        self.assertEqual(self.tooling('{"dependencies": {"react-scripts": "5"}}'), "cra")
        self.assertEqual(self.tooling('{"dependencies": {"react": "19"}}'), "other")
        self.assertEqual(self.tooling('not json'), "other")


class ComputedOrvalConfig(unittest.TestCase):
    """A config only running it resolves: the checks that need its paths say they did not run, rather than
    guessing at src/shared/api."""

    def test_not_run_instead_of_a_finding(self):
        base = Path(__file__).resolve().parent / "clean" / "java-mongo-mvc-standalone"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "p"
            shutil.copytree(base, root)
            (root / "frontend" / "orval.config.ts").write_text(
                "import { out } from './paths'\n"
                "export default { api: { input: '../contracts/openapi.json', output: { target: out.client, "
                "override: { mutator: { path: out.mutator } } } } }\n", encoding="utf-8")
            (root / "frontend" / "src" / "shared" / "api" / "custom-fetch.ts").unlink()
            lint = sl.Lint(root, sl.read_pins(sl.PLUGIN / sl.PINS_REL), {})
            lint.run()
            skipped = {c for nr in lint.not_run for c in nr["checks"]}
            self.assertIn("s8-generated-gitignore", skipped)
            self.assertIn("s8-standalone-base-url", skipped)
            self.assertEqual([f.rule.check for f in lint.findings], [])


class BootParentOp(unittest.TestCase):
    def test_s1_boot_line_carries_set_parent_version(self):
        root = Path(__file__).resolve().parent / "violating" / "kotlin-behind"
        pins = sl.read_pins(sl.PLUGIN / sl.PINS_REL)
        lint = sl.Lint(root, pins, {})
        lint.run()
        boot = [f for f in lint.findings if f.rule.check == "s1-boot-line"]
        self.assertEqual(len(boot), 1)
        self.assertEqual(boot[0].ops, [{"op": "set-parent-version", "pom": "pom.xml", "line": 4,
                                        "groupId": "org.springframework.boot",
                                        "artifactId": "spring-boot-starter-parent", "from": "4.0.8",
                                        "to": pins["spring-boot-starter-parent"]}])
        self.assertTrue(boot[0].mechanical)



class PlatformFloors(unittest.TestCase):
    """The patch floors and the Jackson 3 ceiling at their boundaries, against the live pins: at the pin is
    silent, one patch below fires once, and neither floor rule ever fires beside the rule above it."""

    LAG = Path(__file__).resolve().parent / "violating" / "java-platform-lag"
    KOTLIN = Path(__file__).resolve().parent / "clean" / "kotlin-pges-flux-embedded"

    def setUp(self):
        self.pins = sl.read_pins(sl.PLUGIN / sl.PINS_REL)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def copy(self, base):
        root = Path(self.tmp.name) / "p"
        shutil.copytree(base, root)
        return root

    def lint(self, root):
        lint = sl.Lint(root, self.pins, {})
        lint.run()
        return lint

    @staticmethod
    def checks(lint, check):
        return [f for f in lint.findings if f.rule.check == check]

    @staticmethod
    def edit(path, old, new):
        text = path.read_text(encoding="utf-8")
        assert old in text, (path, old)
        path.write_text(text.replace(old, new), encoding="utf-8")

    @staticmethod
    def one_below(version):
        parts = [int(x) for x in version.split(".")]
        i = max(i for i, x in enumerate(parts) if x > 0)
        parts[i] -= 1
        return ".".join(str(x) for x in parts)

    def test_boot_at_the_pin_is_silent_and_one_patch_below_fires(self):
        root = self.copy(self.LAG)
        pom = root / "pom.xml"
        pin = self.pins["spring-boot-starter-parent"]
        self.edit(pom, "<version>4.1.0</version>", f"<version>{pin}</version>")
        self.assertEqual(self.checks(self.lint(root), "s1-boot-patch"), [])
        if pin.endswith(".0"):
            self.skipTest("the Boot pin is a line's first release: no earlier patch on its line")
        below = self.one_below(pin)
        self.edit(pom, f"<version>{pin}</version>", f"<version>{below}</version>")
        lint = self.lint(root)
        found = self.checks(lint, "s1-boot-patch")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].ops[0]["to"], pin)
        self.assertEqual(self.checks(lint, "s1-boot-line"), [])

    def test_kotlin_at_the_pin_is_silent_below_it_fires_once(self):
        root = self.copy(self.KOTLIN)
        pom = root / "pom.xml"
        pin = self.pins["kotlin.version"]
        self.edit(pom, "<kotlin.version>2.4.99</kotlin.version>", f"<kotlin.version>{pin}</kotlin.version>")
        self.assertEqual(self.checks(self.lint(root), "s1-kotlin-patch"), [])
        self.edit(pom, f"<kotlin.version>{pin}</kotlin.version>",
                  f"<kotlin.version>{self.one_below(pin)}</kotlin.version>")
        lint = self.lint(root)
        self.assertEqual(len(self.checks(lint, "s1-kotlin-patch")), 1)
        self.assertEqual(self.checks(lint, "s1-kotlin-compiler-floor"), [])
        self.assertEqual(self.checks(lint, "s1-kotlin-patch")[0].ops,
                         [{"op": "set-property", "pom": "pom.xml", "name": "kotlin.version",
                           "from": self.one_below(pin), "to": pin}])

    def test_maven_wrapper_at_the_pin_is_silent_and_no_wrapper_is_not_run(self):
        root = self.copy(self.LAG)
        props = root / ".mvn" / "wrapper" / "maven-wrapper.properties"
        pin = self.pins["Maven"]
        self.edit(props, "3.9.9/apache-maven-3.9.9", f"{pin}/apache-maven-{pin}")
        self.assertEqual(self.checks(self.lint(root), "s11-maven-wrapper"), [])
        props.unlink()
        lint = self.lint(root)
        self.assertEqual(self.checks(lint, "s11-maven-wrapper"), [])
        self.assertIn("s11-maven-wrapper", {c for nr in lint.not_run for c in nr["checks"]})

    def test_jackson_on_the_managed_minor_is_silent(self):
        root = self.copy(self.LAG)
        pom = root / "pom.xml"
        self.edit(pom, "<jackson-bom.version>3.2.4</jackson-bom.version>",
                  "<jackson-bom.version>3.1.9</jackson-bom.version>")
        self.edit(pom, "<version>3.2.1</version>", "<version>3.1.9</version>")
        self.assertEqual(self.checks(self.lint(root), "s3.1-jackson3-override"), [])

    def test_jackson_property_read_by_the_import_is_reported_once_where_it_is_set(self):
        found = self.checks(self.lint(self.LAG), "s3.1-jackson3-override")
        self.assertEqual([(f.file, f.line) for f in found], [("pom.xml", 16), ("pom.xml", 78)])
        self.assertTrue(all(not f.mechanical and "FAIL_ON_NULL_FOR_PRIMITIVES" in f.message for f in found))

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
