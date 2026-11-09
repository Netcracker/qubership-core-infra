def inmod($p): $p == $mod or ($p | startswith($mod + "/"));
def step:
  . as $s
  | [ $s.pkgs[] | select(.path as $p | $s.visible | index($p)) | .imports[] ] as $reached
  | .visible = [
      $s.pkgs[].path | . as $p
      | select(
          ([ $s.pkgs[] | select(((.imports + .tests) | index($p))) ] | length) == 0
          or ($reached | index($p))
        )
    ];
[ .[]
  | select(.Module.Path == $mod)
  | {
      path: .ImportPath,
      imports: [(.Imports // [])[] | select(inmod(.))],
      tests: [((.TestImports // []) + (.XTestImports // []))[] | select(inmod(.))]
    }
]
| unique_by(.path)
| {pkgs: ., visible: map(.path)}
| until((.visible | sort) == ((step | .visible) | sort); step)
| .visible[]
