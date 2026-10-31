"""Regenerate shipped icons from the selected master (requires Pillow)."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
MASTER = ROOT / "docs/design/branding/component-vault-logo-ikaros.png"


def square(source: Image.Image, size: int) -> Image.Image:
    return source.resize((size, size), Image.Resampling.LANCZOS)


def save(image: Image.Image, relative: str) -> None:
    image.save(ROOT / relative)


def main() -> None:
    with Image.open(MASTER) as original:
        source = original.convert("RGBA")

    android = "android-client/app/src/main/res"
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        icon = square(source, size)
        save(icon, f"{android}/mipmap-{density}/ic_launcher.png")
        mask = Image.new("L", (size * 4, size * 4))
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        rounded = icon.copy()
        rounded.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        save(rounded, f"{android}/mipmap-{density}/ic_launcher_round.png")

    # 66dp central safe zone within the existing 108dp adaptive foreground.
    foreground = Image.new("RGBA", (432, 432))
    foreground.alpha_composite(square(source, 264), (84, 84))
    save(foreground, f"{android}/drawable-nodpi/ic_launcher_foreground.png")

    windows = "windows-client/ComponentVault.WinUI/Assets"
    for name, size in {"SmallTile": 71, "Square150x150Logo": 150, "Square44x44Logo": 44, "StoreLogo": 50}.items():
        save(square(source, size), f"{windows}/{name}.png")
    for name, width, height in [("Wide310x150Logo", 310, 150), ("SplashScreen", 620, 300)]:
        canvas = Image.new("RGBA", (width, height), "white")
        canvas.alpha_composite(square(source, height), ((width - height) // 2, 0))
        save(canvas, f"{windows}/{name}.png")
    source.save(ROOT / windows / "ComponentVault.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])

    web = "admin-web/public"
    for name, size in {"component-vault-logo": 256, "favicon-32": 32, "apple-touch-icon": 180}.items():
        save(square(source, size), f"{web}/{name}.png")
    source.save(ROOT / web / "favicon.ico", sizes=[(s, s) for s in (16, 32, 48)])


if __name__ == "__main__":
    main()
