#!/usr/bin/env python3
"""Интерактивный стенд настройки гейтов P0: крутишь ползунки — видишь результат.

Та же математика, что detailBlendCv v6 + shrinkage-вариант советчика.
Медленное (bilateral/coh/dir/persist/globalE) считается один раз,
ползунки пересчитывают только веса/гейт/композицию (~0.1-0.3 c).

Требуется: pip install opencv-python numpy

  python tune_gates.py night.jpg
  python tune_gates.py night.jpg --batch --t0 2.2 --t1 6.0 --sigma 24 --out tune_out

Клавиши: ESC — выход, S — сохранить карты и результат рядом с фото.
"""
import argparse
import os
import cv2
import numpy as np


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def lut01(coh, t0, t1):
    return smoothstep((coh - t0) / max(t1 - t0, 1e-6))


def dir_coherence(gray_f, k):
    gx = cv2.Sobel(gray_f, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray_f, cv2.CV_32F, 0, 1, ksize=3)
    exx = cv2.blur(gx * gx, (k, k))
    eyy = cv2.blur(gy * gy, (k, k))
    exy = cv2.blur(gx * gy, (k, k))
    return np.sqrt((exx - eyy) ** 2 + 4.0 * exy ** 2) / (exx + eyy + 1.0)


def immerkaer(yo):
    """Оценка σ шума: Immerkaer, среднее |Laplacian(M)|, M с -2/4."""
    k = np.array([[1, -2, 1], [-2, 4, -2], [1, -2, 1]], np.float32)
    lap = cv2.filter2D(yo, -1, k)
    return float(np.clip(1.2533 * np.mean(np.abs(lap)) / 6.0, 2.0, 30.0))


class Base:
    """Тяжёлые карты: пересчёт только при смене sigma."""

    def __init__(self, bgr, sigma):
        ycc = cv2.cvtColor(bgr, cv2.COLOR_BGR2YCrCb)
        self.yo = ycc[:, :, 0].astype(np.float32)
        if sigma is None or sigma <= 0:
            sigma = immerkaer(self.yo)
            print(f"auto sigma={sigma:.1f} (sCol={3.0 * sigma:.0f}, "
                  f"d={'7' if sigma > 10 else '5'})")
        self.sigma = sigma
        h, w = self.yo.shape
        self.wh = (w, h)
        dd = 7 if sigma > 10 else 5
        self.ys = cv2.bilateralFilter(
            self.yo.astype(np.uint8), dd, sigma, 3.0 * dd).astype(np.float32)
        diff = np.abs(self.yo - self.ys)
        self.coh3 = cv2.blur(diff, (3, 3))
        self.coh5 = cv2.blur(diff, (5, 5))
        self.coh9 = cv2.blur(diff, (9, 9))
        h1 = cv2.pyrDown(diff)
        h2 = cv2.pyrDown(h1)
        low = cv2.resize(cv2.blur(h2, (3, 3)), self.wh,
                         interpolation=cv2.INTER_LINEAR)
        self.prat = low / (self.coh3 + 1.0)
        self.dirc = np.maximum(dir_coherence(self.yo, 5),
                               dir_coherence(self.yo, 9))
        g = self.yo
        for _ in range(3):
            g = cv2.pyrDown(g)
        ggx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3)
        ggy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)
        gmag = cv2.blur(np.abs(ggx) + np.abs(ggy), (5, 5))
        self.ge = cv2.resize(np.minimum(gmag / 30.0, 1.0), self.wh,
                             interpolation=cv2.INTER_LINEAR)


def compose(base, t0, t1, gf, pthr):
    persist = smoothstep((base.prat - pthr) / 0.30)
    w3 = lut01(base.coh3, t0, t1)
    w5 = lut01(base.coh5, t0 * 0.45, t1 * 0.5)
    w9 = lut01(base.coh9, t0 * 0.25, t1 * 0.3)
    amp = np.maximum(np.maximum(w3, w5), w9)
    struct = np.maximum(base.dirc, persist * (0.2 + 0.8 * base.ge))
    gate = smoothstep((struct - gf) / 0.40)
    wf = cv2.blur(amp * gate, (3, 3))
    y_out = base.ys + (base.yo - base.ys) * wf
    tmap = t1 * (1.0 - struct) + 0.5
    y_shr = base.ys + np.sign(base.yo - base.ys) * np.maximum(
        np.abs(base.yo - base.ys) - tmap, 0.0)
    return amp, struct, gate, y_out, y_shr


def batch_mode(img_path, t0, t1, sigma, out):
    os.makedirs(out, exist_ok=True)
    bgr = cv2.imread(img_path, cv2.IMREAD_COLOR)
    assert bgr is not None, "не открылось"
    base = Base(bgr, sigma)
    amp, struct, gate, y_out, y_shr = compose(base, t0, t1, 0.30, 0.55)
    for name, m in (("amp", amp), ("struct", struct), ("gate", gate)):
        cv2.imwrite(f"{out}/{name}.png", (m * 255).astype(np.uint8))
    cv2.imwrite(f"{out}/y_mask.png", np.clip(y_out, 0, 255).astype(np.uint8))
    cv2.imwrite(f"{out}/y_shrink.png", np.clip(y_shr, 0, 255).astype(np.uint8))
    act = lambda m: f"{100.0 * np.mean(m > 0.05):5.1f}%"
    print(f"amp {act(amp)} struct {act(struct)} gate {act(gate)} "
          f"shr-vs-mask {np.mean(np.abs(y_shr - y_out)):.2f}")
    print("OK ->", out)


def interactive(img_path):
    bgr = cv2.imread(img_path, cv2.IMREAD_COLOR)
    assert bgr is not None, "не открылось"
    par = {"t0": 220, "t1": 600, "sigma": 240, "gf": 30, "pthr": 55, "sharp": 30}
    mode = [2]  # 0=mask, 1=shrink, 2=crisp(финал как на телефоне)
    base = [None]
    cache = {}

    def rebuild_base():
        print(f"base: sigma={par['sigma'] / 10:.1f} ...")
        base[0] = Base(bgr, par["sigma"] / 10.0)
        print("base ok")

    def refresh(_=None):
        if base[0] is None:
            rebuild_base()
        amp, struct, gate, y_out, y_shr = compose(
            base[0], par["t0"] / 100.0, par["t1"] / 100.0,
            par["gf"] / 100.0, par["pthr"] / 100.0)
        # Крисп как на телефоне: unsharp radius 1.0, порог 9, только люма.
        blur = cv2.GaussianBlur(y_out, (0, 0), 1.0)
        dd = y_out - blur
        pos = np.maximum(dd - 9.0, 0.0)
        neg = np.maximum(-dd - 9.0, 0.0)
        y_cr = np.clip(y_out + (pos - neg) * (par["sharp"] / 100.0), 0, 255)
        mid = (y_out, y_shr, y_cr)[mode[0]]
        mid_name = ("mask-return", "shrinkage", "crisp-final")[mode[0]]
        cache.update(amp=amp, struct=struct, gate=gate,
                     y_out=y_out, y_shr=y_shr, y_cr=y_cr, mid=mid)
        cv2.imshow("gate/weight (full frame)", gate)
        # Кроп 100% по центру: на ужатом кадре попиксельная разница не видна.
        H, W = base[0].yo.shape
        cw, ch = min(960, W), min(640, H)
        x0, y0c = (W - cw) // 2, (H - ch) // 2
        sl = (slice(y0c, y0c + ch), slice(x0, x0 + cw))
        yo8 = np.clip(base[0].yo[sl], 0, 255).astype(np.uint8)
        m8 = np.clip(mid[sl], 0, 255).astype(np.uint8)
        dm = np.abs(mid[sl] - base[0].yo[sl])
        d8 = cv2.applyColorMap(np.clip(dm * 4.0, 0, 255).astype(np.uint8),
                               cv2.COLORMAP_JET)
        row = np.hstack([cv2.cvtColor(yo8, cv2.COLOR_GRAY2BGR),
                         cv2.cvtColor(m8, cv2.COLOR_GRAY2BGR), d8])
        sc = min(1.0, 1900.0 / row.shape[1])
        if sc < 1.0:
            row = cv2.resize(row, None, fx=sc, fy=sc)
        cv2.imshow(f"crop100%: orig | {mid_name} | diffx4 [V-вид]", row)
        print(f"t0={par['t0']/100:.2f} t1={par['t1']/100:.1f} "
              f"sigma={par['sigma']/10:.1f} gf={par['gf']/100:.2f} "
              f"pthr={par['pthr']/100:.2f} sharp={par['sharp']/100:.2f} "
              f"gate%={100*np.mean(gate>0.05):.1f} |dMid|={np.mean(dm):.2f} "
              f"t0sug={max(1.5, 0.4 * base[0].sigma):.1f}")

    def on_sigma(v):
        par["sigma"] = v
        rebuild_base()
        refresh()

    def make_setter(k):
        return lambda v: (par.__setitem__(k, v), refresh())

    cv2.namedWindow("gate/weight (full frame)")
    cv2.createTrackbar("t0 x0.01", "gate/weight (full frame)", par["t0"], 800, make_setter("t0"))
    cv2.createTrackbar("t1 x0.01", "gate/weight (full frame)", par["t1"], 1500, make_setter("t1"))
    cv2.createTrackbar("sigma x0.1", "gate/weight (full frame)", par["sigma"], 600, on_sigma)
    cv2.createTrackbar("gFloor x0.01", "gate/weight (full frame)", par["gf"], 60, make_setter("gf"))
    cv2.createTrackbar("pThr x0.01", "gate/weight (full frame)", par["pthr"], 70, make_setter("pthr"))
    cv2.createTrackbar("sharp x0.01", "gate/weight (full frame)", par["sharp"], 100, make_setter("sharp"))
    rebuild_base()
    refresh()
    print("ESC — выход, S — сохранить, V — вид средней панели (mask/shrink/crisp)")
    while True:
        k = cv2.waitKey(50) & 0xFF
        if k == 27:
            break
        if k in (ord("v"), ord("V"), ord("м"), ord("М")):
            mode[0] = (mode[0] + 1) % 3
            refresh()
            continue
        if k in (ord("s"), ord("S"), ord("ы"), ord("Ы")):
            d = os.path.splitext(img_path)[0] + "_tune"
            os.makedirs(d, exist_ok=True)
            for name in ("amp", "struct", "gate"):
                cv2.imwrite(f"{d}/{name}.png", (cache[name] * 255).astype(np.uint8))
            cv2.imwrite(f"{d}/y_mid.png", np.clip(cache["mid"], 0, 255).astype(np.uint8))
            cv2.imwrite(f"{d}/y_shrink.png", np.clip(cache["y_shr"], 0, 255).astype(np.uint8))
            cv2.imwrite(f"{d}/y_crisp.png", np.clip(cache["y_cr"], 0, 255).astype(np.uint8))
            print("saved ->", d)
    cv2.destroyAllWindows()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("img")
    ap.add_argument("--batch", action="store_true")
    ap.add_argument("--t0", type=float, default=2.2)
    ap.add_argument("--t1", type=float, default=6.0)
    ap.add_argument("--sigma", type=float, default=0.0,
                    help="sigma bilateral; 0 = авто Immerkaer как на телефоне")
    ap.add_argument("--out", default="tune_out")
    a = ap.parse_args()
    if a.batch:
        batch_mode(a.img, a.t0, a.t1, a.sigma, a.out)
    else:
        interactive(a.img)


if __name__ == "__main__":
    main()
