"""Does the classifier read a whole cell better than the piece of ink we cut out of it?

The shipped reader hands its model an MNIST-style rendering: threshold the square, keep
the chosen blobs, crop to their bounding box, scale the longest side to 20 and centre it
by mass. That throws away where the digit sat, how big it was, and everything else in the
square - and it is what produced the fragment fault, where a broken 7 reached the model as
a bare crossbar filling the frame.

This measures the other bet: give the model the whole square, contrast-stretched and
resized, and let it find the digit itself. Both arms are scored the way the shipped number
is - leave one photograph out - over the same cells, with the same seeds.

The synthetic sources have to be re-rendered for the cell arm or the comparison is rigged:
MNIST and the drawn digits are glyphs that fill their frame, and a model trained on those
and then shown a square with a small digit off to one side is being asked a question it was
never taught. So every glyph is placed back into a cell at a size and offset drawn from
what the corpus actually shows, on paper with noise, sometimes with a grid-line remnant, a
stray mark, or the ghost of an erased digit under it.

    ../../.venv/Scripts/python wholecell.py --only red     # a fast subset
    ../../.venv/Scripts/python wholecell.py                # the full round
"""
import os
import sys

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train as T                                                          # noqa: E402
from cells import CELLS, load_labels, normalised_cells                     # noqa: E402

DEVICE = T.DEVICE
SEED = T.SEED

#: Where a digit sits in its square, measured over the 1450 labelled digits of the corpus.
#: The longest side as a fraction of the cell, and the centre offset from the cell centre.
#: Print is set in one size and sits still; handwriting is half again as large and wanders,
#: and its top decile reaches the cell edge, which is why the cap is above one.
GEOMETRY = {
    "print": dict(size=(0.673, 0.070), spread=0.040, cap=(0.45, 0.88)),
    "hand": dict(size=(0.839, 0.110), spread=0.075, cap=(0.55, 1.05)),
}

#: The glyph's longest side in the MNIST convention: 20 pixels of 28.
GLYPH_FRACTION = 20.0 / 28.0


def cell_bitmaps(stem):
    """One photograph's squares as the whole-cell arm sees them: 28x28, ink towards one.

    Contrast is stretched per square rather than per page, which is the argument the
    reader's own contrast measurement already makes: a crease or a shadow moves a square
    and its paper together, and dividing by the square's own range leaves it alone. The
    floor stops an empty square from having its paper texture amplified into ink.
    """
    directory = os.path.join(CELLS, stem)
    if not os.path.isdir(directory):
        return {}
    out = {}
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".png"):
            continue
        index = int(name[len("cell_"):-len(".png")])
        a = np.array(
            Image.open(os.path.join(directory, name)).resize((28, 28), Image.BILINEAR),
            dtype=np.float32)
        paper = float(np.median(a))
        denom = max(paper - float(np.percentile(a, 2)), 12.0)
        out[index] = np.clip((paper - a) / denom, 0, 1).astype(np.float32)
    return out


def corpus(arm):
    """Every labelled digit, in whichever form the arm under test reads.

    The two arms are held to the same cells on purpose. The whole-cell arm could also be
    given the squares where the reader finds no ink at all, which would be a real
    advantage - but it would not be this comparison, so it is measured separately.
    """
    xs, ys, kinds, photos, squares = [], [], [], [], []
    for stem, labelled in load_labels().items():
        mine = cell_bitmaps(stem) if arm == "cell" else normalised_cells(stem)
        theirs = normalised_cells(stem) if arm == "cell" else cell_bitmaps(stem)
        if not mine or not theirs:
            continue
        for i in range(81):
            digit, source = labelled[i]
            if digit is None or i not in mine or i not in theirs:
                continue
            xs.append(mine[i])
            ys.append(digit - 1)
            kinds.append("print" if source == "given" else "hand")
            photos.append(stem)
            squares.append(i)
    return np.stack(xs), np.array(ys, dtype=np.int64), kinds, photos, squares


def _affine(images, scale, shift_y, shift_x, generator):
    """Place each image into its frame at the given scale and offset."""
    size = images.shape[0]
    idx = torch.arange(28, device=DEVICE, dtype=torch.float32)
    out_y = (idx.view(1, -1, 1) - 13.5).expand(size, 28, 28)
    out_x = (idx.view(1, 1, -1) - 13.5).expand(size, 28, 28)
    s = scale.view(-1, 1, 1)
    in_y = out_y / s + 13.5 - shift_y.view(-1, 1, 1) * 28
    in_x = out_x / s + 13.5 - shift_x.view(-1, 1, 1) * 28
    grid = torch.stack(((in_x + 0.5) * 2 / 28 - 1, (in_y + 0.5) * 2 / 28 - 1), dim=-1)
    return F.grid_sample(images, grid, align_corners=False, padding_mode="zeros")


def into_cells(glyphs, kind, seed, ghosts=None, batch=8192):
    """Put glyphs back into squares: the training input the whole-cell arm needs.

    A model shown only glyphs that fill their frame has been taught that whatever ink it
    can see is the digit. In a real square the digit is two thirds of the height, sits off
    centre, and shares the paper with candidate marks, grid-line remnants and rubbed-out
    pencil. All of that is put back here, because the arm is only worth measuring if it is
    asked the question it will actually be asked.
    """
    g = GEOMETRY[kind]
    rng = torch.Generator(device=DEVICE).manual_seed(seed)
    source = torch.tensor(glyphs, dtype=torch.float32, device=DEVICE)
    faint_source = None if ghosts is None else torch.tensor(
        ghosts, dtype=torch.float32, device=DEVICE)
    idx = torch.arange(28, device=DEVICE, dtype=torch.float32)
    pieces = []

    for start in range(0, len(source), batch):
        images = source[start:start + batch].unsqueeze(1)
        n = len(images)

        want = torch.empty(n, device=DEVICE).normal_(g["size"][0], g["size"][1], generator=rng)
        want = want.clamp(*g["cap"])
        placed = _affine(
            images, want / GLYPH_FRACTION,
            torch.empty(n, device=DEVICE).normal_(0, g["spread"], generator=rng),
            torch.empty(n, device=DEVICE).normal_(0, g["spread"], generator=rng), rng)

        # The ghost of a rubbed-out digit, faint and not necessarily under this one.
        if faint_source is not None:
            which = torch.randint(len(faint_source), (n,), device=DEVICE, generator=rng)
            ghost = _affine(
                faint_source[which].unsqueeze(1), want / GLYPH_FRACTION,
                torch.empty(n, device=DEVICE).normal_(0, 0.10, generator=rng),
                torch.empty(n, device=DEVICE).normal_(0, 0.10, generator=rng), rng)
            level = torch.empty(n, 1, 1, 1, device=DEVICE).uniform_(0.10, 0.40, generator=rng)
            on = (torch.rand(n, 1, 1, 1, device=DEVICE, generator=rng) < 0.15).float()
            placed = torch.maximum(placed, ghost * level * on)

        # Stray marks: a candidate written small in a corner, or a speck of the page.
        for _ in range(2):
            at_y = torch.empty(n, 1, 1, device=DEVICE).uniform_(0, 27, generator=rng)
            at_x = torch.empty(n, 1, 1, device=DEVICE).uniform_(0, 27, generator=rng)
            radius = torch.empty(n, 1, 1, device=DEVICE).uniform_(0.8, 2.2, generator=rng)
            away = (idx.view(1, -1, 1) - at_y) ** 2 + (idx.view(1, 1, -1) - at_x) ** 2
            level = torch.empty(n, 1, 1, device=DEVICE).uniform_(0.15, 0.65, generator=rng)
            on = (torch.rand(n, 1, 1, device=DEVICE, generator=rng) < 0.20).float()
            mark = torch.exp(-away / (2 * radius ** 2)) * level * on
            placed = torch.maximum(placed, mark.unsqueeze(1))

        # What is left of a grid line after the twelve per cent inset: an edge, sometimes.
        near = torch.minimum(idx, 27 - idx).view(1, -1, 1)
        thick = torch.randint(1, 3, (n, 1, 1), device=DEVICE, generator=rng).float()
        level = torch.empty(n, 1, 1, device=DEVICE).uniform_(0.25, 0.95, generator=rng)
        vertical = (torch.rand(n, 1, 1, device=DEVICE, generator=rng) < 0.5).float()
        on = (torch.rand(n, 1, 1, device=DEVICE, generator=rng) < 0.25).float()
        rows = (near < thick).float().expand(n, 28, 28)
        columns = (torch.minimum(idx, 27 - idx).view(1, 1, -1) < thick).float().expand(n, 28, 28)
        line = (vertical * columns + (1 - vertical) * rows).clamp(0, 1) * level * on
        placed = torch.maximum(placed, line.unsqueeze(1))

        placed = T._blur(placed, 0.7)
        speck = torch.empty_like(placed).normal_(0, 1, generator=rng)
        level = torch.empty(n, 1, 1, 1, device=DEVICE).uniform_(0.01, 0.09, generator=rng)
        pieces.append((placed + speck * level).clamp(0, 1).cpu())

    return torch.cat(pieces).numpy()[:, 0]


def amplify_cells(x, y, times, seed=SEED + 4, batch=8192):
    """[train.amplify_on_gpu] for whole squares: no crop, because the crop is the thing.

    The same elastic warp, rotation and zoom, but the digit is left where it is in its
    square and the paper is jittered instead - a photograph of the same cell under a
    different lamp is a real variation, and cropping to the ink would delete it.
    """
    source = torch.tensor(x, dtype=torch.float32, device=DEVICE)
    count = len(source)
    rng = torch.Generator(device=DEVICE).manual_seed(seed)
    idx = torch.arange(28, device=DEVICE, dtype=torch.float32)
    pieces = []

    for start in range(0, count * times, batch):
        size = min(batch, count * times - start)
        which = torch.arange(start, start + size, device=DEVICE) // times
        images = source[which].unsqueeze(1)

        strength = torch.empty(size, 1, 1, 1, device=DEVICE).uniform_(2, 5, generator=rng)
        noise = torch.empty(size, 2, 28, 28, device=DEVICE).uniform_(-1, 1, generator=rng)
        displacement = T._blur(noise, 4.0) * strength

        degrees = torch.empty(size, device=DEVICE).uniform_(-12, 12, generator=rng)
        angle = degrees * (torch.pi / 180)
        zoom = torch.empty(size, device=DEVICE).uniform_(0.90, 1.10, generator=rng)
        cos = (angle.cos() / zoom).view(-1, 1, 1)
        sin = (angle.sin() / zoom).view(-1, 1, 1)
        shift_y = torch.empty(size, 1, 1, device=DEVICE).uniform_(-1.5, 1.5, generator=rng)
        shift_x = torch.empty(size, 1, 1, device=DEVICE).uniform_(-1.5, 1.5, generator=rng)

        out_y = (idx.view(1, -1, 1) - 13.5).expand(size, 28, 28)
        out_x = (idx.view(1, 1, -1) - 13.5).expand(size, 28, 28)
        in_y = cos * out_y - sin * out_x + 13.5 + displacement[:, 0] + shift_y
        in_x = sin * out_y + cos * out_x + 13.5 + displacement[:, 1] + shift_x
        grid = torch.stack(((in_x + 0.5) * 2 / 28 - 1, (in_y + 0.5) * 2 / 28 - 1), dim=-1)

        warped = F.grid_sample(images, grid, align_corners=False, padding_mode="zeros")
        # Paper and pen vary between photographs; the crop used to hide that.
        gain = torch.empty(size, 1, 1, 1, device=DEVICE).uniform_(0.75, 1.25, generator=rng)
        speck = torch.empty_like(warped).normal_(0, 1, generator=rng)
        level = torch.empty(size, 1, 1, 1, device=DEVICE).uniform_(0.01, 0.06, generator=rng)
        pieces.append((warped * gain + speck * level).clamp(0, 1).cpu())

    return torch.cat(pieces).numpy()[:, 0], np.repeat(y, times)


CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build", "sources.npz")


def glyph_sources():
    """The synthetic sources, kept apart so the cell arm can place each one properly.

    Printed digits go into a square at the size print occupies; everything else is
    handwriting and goes in at the size handwriting occupies. Cached, because rendering a
    hundred and twenty fonts and drawing ten thousand digits takes minutes and neither arm
    varies it.
    """
    if os.path.isfile(CACHE):
        d = np.load(CACHE)
        return d["hand_x"], d["hand_y"], d["print_x"], d["print_y"]
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    print("loading MNIST ...")
    xm, ym = T.mnist_tensors(True)
    xm = T.augment_mnist(xm)
    print("rendering printed digits ...")
    xp, yp = T.synthetic_printed(T.usable_fonts())
    print("drawing continental ones, sevens, nines and eights ...")
    x1, y1 = T.continental_ones()
    x7, y7 = T.continental_sevens()
    x9, y9 = T.curl(xm, ym)
    x8, y8 = T.written_eights()
    hand_x = np.concatenate([xm[:, 0], x1, x7, x9, x8]).astype(np.float32)
    hand_y = np.concatenate([ym, y1, y7, y9, y8])
    np.savez_compressed(CACHE, hand_x=hand_x, hand_y=hand_y,
                        print_x=xp.astype(np.float32), print_y=yp)
    return hand_x, hand_y, xp, yp


def base_for(arm):
    """The non-corpus training set, in the form this arm reads."""
    hand_x, hand_y, print_x, print_y = glyph_sources()
    if arm == "blob":
        return np.concatenate([hand_x, print_x])[:, None], np.concatenate([hand_y, print_y])
    ghosts = hand_x[:4000]
    return (np.concatenate([into_cells(hand_x, "hand", SEED + 11, ghosts),
                            into_cells(print_x, "print", SEED + 12, ghosts)])[:, None],
            np.concatenate([hand_y, print_y]))


def run(arm, only):
    x, y, kinds, photos, squares = corpus(arm)
    hand = np.array([k == "hand" for k in kinds])
    print(f"\n=== {arm} arm: {len(x)} labelled digits "
          f"({(~hand).sum()} printed, {hand.sum()} handwritten) ===")
    base_x, base_y = base_for(arm)

    misses, scored = [], 0
    for stem in sorted(set(photos)):
        if only is not None and only not in stem:
            continue
        held = np.array([p == stem for p in photos])
        keep = ~held
        # Seeds are passed rather than left to default, because a default argument is
        # bound when the function is defined: reassigning the module's SEED afterwards
        # changes nothing, and a "second seed" run that varies neither the training
        # initialisation nor the augmentation is not a second seed at all.
        if arm == "blob":
            ax, ay = T.amplify_on_gpu(x[keep][:, None], y[keep], T.CORPUS_TIMES, seed=SEED + 4)
        else:
            ax, ay = amplify_cells(x[keep], y[keep], T.CORPUS_TIMES, seed=SEED + 4)
        model = T.fit(np.concatenate([base_x, ax[:, None]]),
                      np.concatenate([base_y, ay]), seed=SEED)

        tx = torch.tensor(x[held][:, None])
        ty = torch.tensor(y[held])
        model.eval()
        with torch.no_grad():
            pred = model(tx.to(DEVICE)).argmax(1).cpu()
        wrong = (pred != ty).numpy()
        scored += int(held.sum())
        here = [(s, k) for s, k, h in zip(squares, kinds, held) if h]
        for i in np.where(wrong)[0]:
            misses.append((stem, here[i][0], int(ty[i]) + 1, int(pred[i]) + 1, here[i][1]))
        print(f"  {stem:<40} {int(held.sum()) - int(wrong.sum())}/{int(held.sum())}"
              f"   wrong {int(wrong.sum())}")

    by_kind = {"hand": 0, "print": 0}
    for m in misses:
        by_kind[m[4]] += 1
    print(f"  {arm}: {scored - len(misses)}/{scored} = {(scored - len(misses)) / scored:.4f}"
          f"   wrong {len(misses)} ({by_kind['hand']} hand, {by_kind['print']} print)")
    return misses, scored


def montage(images, columns=16):
    """A sheet of cells to look at, because a number cannot say whether these look real."""
    rows = (len(images) + columns - 1) // columns
    sheet = np.zeros((rows * 30 + 2, columns * 30 + 2), dtype=np.float32)
    for i, a in enumerate(images):
        r, c = divmod(i, columns)
        sheet[r * 30 + 2:r * 30 + 30, c * 30 + 2:c * 30 + 30] = a
    return Image.fromarray((255 - sheet * 255).astype(np.uint8))


def write_montages(where="build"):
    """Synthetic squares beside real ones. Run this before believing any of the numbers.

    The placement is drawn from measurements, and measurements of size and offset can be
    right while the result still looks nothing like a photograph. This is the check that
    caught nothing in the end, which is the only reason the numbers below were worth
    reading at all.
    """
    os.makedirs(where, exist_ok=True)
    hand_x, _, print_x, _ = glyph_sources()
    ghosts = hand_x[:4000]
    rng = np.random.default_rng(1)
    montage(into_cells(hand_x[rng.choice(len(hand_x), 64, replace=False)], "hand", 5, ghosts)
            ).save(os.path.join(where, "synthetic-hand.png"))
    montage(into_cells(print_x[rng.choice(len(print_x), 64, replace=False)], "print", 6, ghosts)
            ).save(os.path.join(where, "synthetic-print.png"))

    real = {"hand": [], "print": []}
    for stem, labelled in load_labels().items():
        bitmaps = cell_bitmaps(stem)
        for i in range(81):
            digit, source = labelled[i]
            if digit is not None and i in bitmaps:
                real["print" if source == "given" else "hand"].append(bitmaps[i])
    for kind, images in real.items():
        rng.shuffle(images)
        montage(images[:64]).save(os.path.join(where, f"real-{kind}.png"))
    print(f"wrote four montages under {where}/")


def main():
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    if "--montage" in sys.argv:
        write_montages()
        return
    if "--seed" in sys.argv:
        # A six-cell gap on 1450 is the size a seed can move on its own, so the run has
        # to be repeatable at a different one before the gap means anything.
        global SEED
        SEED = T.SEED = int(sys.argv[sys.argv.index("--seed") + 1])
    arms = ["blob", "cell"]
    if "--cell-only" in sys.argv:
        arms = ["cell"]
    if "--blob-only" in sys.argv:
        arms = ["blob"]

    out = {}
    for arm in arms:
        out[arm] = run(arm, only)

    if len(out) == 2:
        print("\n=== side by side, same cells, same folds ===")
        for arm in arms:
            misses, scored = out[arm]
            print(f"  {arm:<5} {scored - len(misses)}/{scored} wrong {len(misses)}")
        a = {(m[0], m[1]) for m in out["blob"][0]}
        b = {(m[0], m[1]) for m in out["cell"][0]}
        print(f"  wrong in both: {len(a & b)}   only blob: {len(a - b)}   only cell: {len(b - a)}")
        print("\n  cells the whole-cell arm fixes:")
        for m in sorted(out["blob"][0]):
            if (m[0], m[1]) in a - b:
                print(f"    FIXED\t{m[0]}\t{m[1]}\ttruth {m[2]}, blob read {m[3]}\t{m[4]}")
        print("\n  cells the whole-cell arm breaks:")
        for m in sorted(out["cell"][0]):
            if (m[0], m[1]) in b - a:
                print(f"    BROKE\t{m[0]}\t{m[1]}\ttruth {m[2]}, cell read {m[3]}\t{m[4]}")


if __name__ == "__main__":
    main()
