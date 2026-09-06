"""Show the classifier the square and the cut-out at once, and see if it beats either.

Measuring the two separately said the interesting thing. Whole square 45 wrong on unseen
photographs, the cut-out blob 51, but only 15 of those are the same cell: they are not a
better and a worse reader, they are two readers that fail at different things. The blob
crop deletes faint ink and hands over fragments; the whole square keeps the erasures, the
stray marks and the grid remnants and reads them as digits. Neither fault is present in
the other's input.

So this arm gives the model both as two channels of the same 28x28 - the square as
photographed, and the blob rendering the shipped reader cuts from it - and lets the
convolutions use whichever one carries the answer. It costs one input channel, about a
hundred and fifty more parameters, and no new photographs.

The trap it has to avoid is being taught that the second channel is always right. If the
synthetic half pairs every square with a clean, whole glyph, the model learns to read the
cut-out and ignore the square, and the fragment fault returns intact. So the blob channel
of a synthetic cell is *derived from that cell* by the same procedure the reader uses -
largest component, gather the pieces of the same ink, crop, centre - which means the
synthetic half breaks digits into fragments at whatever rate the rendering does, on its
own, without being told to.

    ../../.venv/Scripts/python twochannel.py --only red
    ../../.venv/Scripts/python twochannel.py
"""
import os
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from scipy import ndimage

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train as T                                                          # noqa: E402
import wholecell as W                                                      # noqa: E402
from cells import load_labels, normalised_cells                            # noqa: E402

DEVICE = T.DEVICE

#: Ink threshold on a contrast-stretched square. The square is already scaled so that its
#: darkest ink is one and its paper is zero, so this is a fraction of the square's own
#: range rather than a grey level, and it does not need to move with the lighting.
INK = 0.40

#: How much of the main piece's mean ink another piece must carry to be the same pen, and
#: how far away it may be. Both are [CellAnalyzer]'s, which is the point - the channel has
#: to be built the way the reader builds it or the model is being trained on a fiction.
SAME_INK_FRACTION = 0.80
SAME_GLYPH_GAP = 1.4


class Net2(nn.Module):
    """[train.Net] with two input channels instead of one. Nothing else differs.

    Deliberately the same everywhere else. If a two-channel model wins, the question is
    whether the second channel is worth having, and a wider or deeper network would make
    that impossible to read off the result.
    """

    def __init__(self):
        super().__init__()
        self.c1 = nn.Conv2d(2, 16, 3, padding=1)
        self.c2 = nn.Conv2d(16, 32, 3, padding=1)
        self.fc1 = nn.Linear(32 * 7 * 7, 64)
        self.drop = nn.Dropout(0.3)
        self.fc2 = nn.Linear(64, 9)

    def forward(self, x):
        x = F.max_pool2d(F.relu(self.c1(x)), 2)
        x = F.max_pool2d(F.relu(self.c2(x)), 2)
        x = x.flatten(1)
        x = self.drop(F.relu(self.fc1(x)))
        return self.fc2(x)


def centre_mask(mask):
    """A binary mask cropped, scaled to 20 and centred by mass in 28, as the reader does.

    Area resampling rather than bilinear, for the reason CellAnalyzer gives: at this
    reduction bilinear samples a small neighbourhood wherever it lands and turns a thin
    stroke into a broken one, where averaging the whole source region turns it grey.
    """
    ys, xs = np.where(mask)
    if len(ys) == 0:
        return np.zeros((28, 28), dtype=np.float32)
    piece = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1].astype(np.float32)
    h, w = piece.shape
    scale = 20.0 / max(h, w)
    nh, nw = max(1, int(round(h * scale))), max(1, int(round(w * scale)))
    small = np.array(
        Image.fromarray((piece * 255).astype(np.uint8)).resize((nw, nh), Image.BOX)
    ).astype(np.float32) / 255.0
    out = np.zeros((28, 28), dtype=np.float32)
    if small.sum() <= 0:
        return out
    cy, cx = ndimage.center_of_mass(small)
    top = int(round(14 - cy))
    left = int(round(14 - cx))
    top = max(0, min(28 - nh, top))
    left = max(0, min(28 - nw, left))
    out[top:top + nh, left:left + nw] = small
    return out


def blob_of(cell):
    """The blob rendering the reader would cut from this square.

    The same shape of decision as [CellAnalyzer]: label the ink, take the largest piece,
    then gather the pieces that are close to it and written in the same ink, and normalise
    the union. It runs on the 28x28 square rather than the ninety-pixel one the phone has,
    so it breaks digits a little more readily than the real thing does - which errs
    towards giving this arm a *worse* second channel than it will have in practice.
    """
    ink = cell > INK
    labels, count = ndimage.label(ink)
    if count == 0:
        return np.zeros((28, 28), dtype=np.float32)
    areas = ndimage.sum(ink, labels, range(1, count + 1))
    main = int(np.argmax(areas)) + 1
    means = ndimage.mean(cell, labels, range(1, count + 1))
    floor = means[main - 1] * SAME_INK_FRACTION
    boxes = ndimage.find_objects(labels)

    taken = {main}
    grew = True
    while grew:
        grew = False
        for piece in range(1, count + 1):
            if piece in taken or means[piece - 1] < floor:
                continue
            ys, xs = boxes[piece - 1]
            close = False
            for other in taken:
                oy, ox = boxes[other - 1]
                gap_y = max(0, max(ys.start, oy.start) - min(ys.stop, oy.stop))
                gap_x = max(0, max(xs.start, ox.start) - min(xs.stop, ox.stop))
                if gap_y <= SAME_GLYPH_GAP and gap_x <= SAME_GLYPH_GAP:
                    close = True
                    break
            if close:
                taken.add(piece)
                grew = True
    return centre_mask(np.isin(labels, list(taken)))


def blobs_for(cells, note=""):
    out = np.empty_like(cells)
    for i in range(len(cells)):
        out[i] = blob_of(cells[i])
        if note and i % 20000 == 0:
            print(f"  {note} {i}/{len(cells)}")
    return out


def paired_corpus():
    """Every labelled digit as both channels: the square, and the reader's own cut-out.

    The second channel here is the real one, exported from Kotlin, not the derivation
    above - on the corpus the reader's actual output is available and there is no reason
    to approximate it.
    """
    cells, blobs, ys, kinds, photos, squares = [], [], [], [], [], []
    for stem, labelled in load_labels().items():
        square = W.cell_bitmaps(stem)
        cut = normalised_cells(stem)
        if not square or not cut:
            continue
        for i in range(81):
            digit, source = labelled[i]
            if digit is None or i not in square or i not in cut:
                continue
            cells.append(square[i])
            blobs.append(cut[i])
            ys.append(digit - 1)
            kinds.append("print" if source == "given" else "hand")
            photos.append(stem)
            squares.append(i)
    return (np.stack(cells), np.stack(blobs), np.array(ys, dtype=np.int64),
            kinds, photos, squares)


def amplify_paired(cells, blobs, y, times, seed, batch=8192):
    """Augment both channels of a cell under one distortion, not two.

    The channels are two views of the same square, so they have to move together: warping
    them independently would teach the model that the cut-out is unrelated to the square
    it came from. The square is left where it is afterwards and the cut-out is re-centred,
    because that is what each one is.
    """
    a = torch.tensor(cells, dtype=torch.float32, device=DEVICE)
    b = torch.tensor(blobs, dtype=torch.float32, device=DEVICE)
    count = len(a)
    rng = torch.Generator(device=DEVICE).manual_seed(seed)
    idx = torch.arange(28, device=DEVICE, dtype=torch.float32)
    out_a, out_b = [], []

    for start in range(0, count * times, batch):
        size = min(batch, count * times - start)
        which = torch.arange(start, start + size, device=DEVICE) // times
        pair = torch.stack([a[which], b[which]], dim=1)

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

        warped = F.grid_sample(pair, grid, align_corners=False, padding_mode="zeros")

        square = warped[:, :1]
        gain = torch.empty(size, 1, 1, 1, device=DEVICE).uniform_(0.75, 1.25, generator=rng)
        speck = torch.empty_like(square).normal_(0, 1, generator=rng)
        level = torch.empty(size, 1, 1, 1, device=DEVICE).uniform_(0.01, 0.06, generator=rng)
        out_a.append((square * gain + speck * level).clamp(0, 1).cpu())
        out_b.append(T._centre_on_mass(warped[:, 1:].clamp(0, 1)).cpu())

    return (torch.cat(out_a).numpy()[:, 0], torch.cat(out_b).numpy()[:, 0],
            np.repeat(y, times))


BASE_CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build", "paired.npz")


def paired_base():
    """The synthetic half as two channels, the second derived from the first."""
    if os.path.isfile(BASE_CACHE):
        d = np.load(BASE_CACHE)
        return d["cells"], d["blobs"], d["y"]
    hand_x, hand_y, print_x, print_y = W.glyph_sources()
    ghosts = hand_x[:4000]
    cells = np.concatenate([W.into_cells(hand_x, "hand", W.SEED + 11, ghosts),
                            W.into_cells(print_x, "print", W.SEED + 12, ghosts)])
    y = np.concatenate([hand_y, print_y])
    print(f"deriving the cut-out channel for {len(cells)} synthetic cells ...")
    blobs = blobs_for(cells, note="synthetic")
    os.makedirs(os.path.dirname(BASE_CACHE), exist_ok=True)
    np.savez_compressed(BASE_CACHE, cells=cells, blobs=blobs, y=y)
    return cells, blobs, y


def fit2(x, y, epochs=6, seed=T.SEED, batch=256):
    """[train.fit] with the two-channel network, taking tensors already on the card."""
    torch.manual_seed(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    model = Net2().to(DEVICE)
    optimiser = torch.optim.Adam(model.parameters(), lr=1e-3)
    count = len(x)
    for _ in range(epochs):
        model.train()
        order = torch.randperm(count, device=DEVICE)
        for start in range(0, count, batch):
            chosen = order[start:start + batch]
            optimiser.zero_grad()
            F.cross_entropy(model(x[chosen]), y[chosen]).backward()
            optimiser.step()
    return model


def main():
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    seed = int(sys.argv[sys.argv.index("--seed") + 1]) if "--seed" in sys.argv else T.SEED

    cells, blobs, y, kinds, photos, squares = paired_corpus()
    hand = np.array([k == "hand" for k in kinds])
    print(f"=== two-channel arm: {len(cells)} labelled digits "
          f"({(~hand).sum()} printed, {hand.sum()} handwritten) ===")

    base_cells, base_blobs, base_y = paired_base()
    base = torch.tensor(np.stack([base_cells, base_blobs], axis=1), dtype=torch.float32)
    base_labels = torch.tensor(base_y)

    # Augmented once for the whole corpus and selected per fold, rather than augmented per
    # fold: the distortion of a cell does not depend on which photograph is being held out,
    # and doing it once turns twenty-five augmentations into one.
    print("augmenting the corpus, both channels under one distortion ...")
    aug_cells, aug_blobs, aug_y = amplify_paired(cells, blobs, y, T.CORPUS_TIMES, seed + 4)
    aug_photo = np.repeat(np.array(photos), T.CORPUS_TIMES)
    aug = torch.tensor(np.stack([aug_cells, aug_blobs], axis=1), dtype=torch.float32)
    aug_labels = torch.tensor(aug_y)

    test = torch.tensor(np.stack([cells, blobs], axis=1), dtype=torch.float32)
    misses, scored = [], 0

    for stem in sorted(set(photos)):
        if only is not None and only not in stem:
            continue
        keep = torch.tensor(aug_photo != stem)
        x = torch.cat([base, aug[keep]]).to(DEVICE)
        labels = torch.cat([base_labels, aug_labels[keep]]).to(DEVICE)
        model = fit2(x, labels, seed=seed)
        del x, labels
        torch.cuda.empty_cache()

        held = np.array([p == stem for p in photos])
        tx = test[held]
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
    print(f"  two-channel: {scored - len(misses)}/{scored} = "
          f"{(scored - len(misses)) / scored:.4f}   wrong {len(misses)} "
          f"({by_kind['hand']} hand, {by_kind['print']} print)")
    print()
    for m in sorted(misses):
        print(f"    MISS\t{m[0]}\t{m[1]}\ttruth {m[2]}, read {m[3]}\t{m[4]}")


if __name__ == "__main__":
    main()
