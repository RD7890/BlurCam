"""Offline harness: runs the SHIPPED GLSL (extracted from Shaders.kt) + real Depth Anything V2 on a still.
Usage: python harness.py <model.onnx> [out_prefix]"""
import re, sys, math, numpy as np, cv2, moderngl, onnxruntime as ort
from skimage import data

KT = __file__.replace('tools/harness/harness.py', 'app/src/main/java/com/blurcam/app/pipeline/Shaders.kt')

def shader(name):
    src = open(KT).read()
    m = re.search(r'const val %s = """(.*?)"""' % name, src, re.S)
    s = m.group(1)
    s = s.replace('#version 300 es', '#version 330 core')
    s = re.sub(r'^precision .*?;\n', '', s, flags=re.M)
    return s

def round14(v): return max(14, int(round(v / 14.0)) * 14)

def depth_input(rgb, S):
    h, w = rgb.shape[:2]
    if w <= h: W = round14(S); H = round14(S * h / w)
    else: H = round14(S); W = round14(S * w / h)
    img = cv2.resize(rgb, (W, H), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
    img = (img - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    return img.transpose(2, 0, 1)[None].astype(np.float32)

def normalize(D):
    lo, hi = np.percentile(D, 2), np.percentile(D, 98)
    return np.clip((D - lo) / max(hi - lo, 1e-6), 0, 1).astype(np.float32)

def auto_focus(d):
    h, w = d.shape
    ys, xs = np.mgrid[0:h, 0:w]
    u, v = (xs + .5) / w, (ys + .5) / h
    inside = (u > .2) & (u < .8) & (v > .15) & (v < .85)
    wt = np.exp(-(((u - .5) / .25) ** 2 + ((v - .5) / .3) ** 2) / 2) * inside
    hist = np.zeros(32)
    b = np.minimum((d * 32).astype(int), 31)
    np.add.at(hist, b[inside], wt[inside])
    hist /= hist.sum()
    acc = 0
    for k in range(31, -1, -1):          # nearest first
        acc += hist[k]
        if acc >= 0.12:
            sel = (b >= max(0, k - 2)) & (b <= min(31, k + 2)) & inside
            return float(np.median(d[sel])), k
    return float(np.median(d[inside])), 0

def grow_subject(d, seed, f, tau=0.035, T=0.30):
    """Region-grow over smooth depth from the seed: the whole person (head+body) becomes one in-focus plane."""
    from collections import deque
    h, w = d.shape
    sx, sy = seed
    mask = np.zeros((h, w), bool)
    if abs(d[sy, sx] - f) > T: return mask, f
    q = deque([(sx, sy)]); mask[sy, sx] = True
    while q:
        x, y = q.popleft(); v = d[y, x]
        for nx, ny in ((x+1,y),(x-1,y),(x,y+1),(x,y-1)):
            if 0 <= nx < w and 0 <= ny < h and not mask[ny, nx]:
                u = d[ny, nx]
                if abs(u - v) < tau and abs(u - f) < T:
                    mask[ny, nx] = True; q.append((nx, ny))
    return mask, float(np.median(d[mask]))

def auto_seed(d, f):
    h, w = d.shape
    ys, xs = np.mgrid[0:h, 0:w]
    u, v = (xs + .5) / w, (ys + .5) / h
    cand = (np.abs(d - f) < 0.06) & (u > .15) & (u < .85) & (v > .1) & (v < .9)
    dist = np.where(cand, (u - .5) ** 2 + (v - .5) ** 2, 9)
    iy, ix = np.unravel_index(np.argmin(dist), dist.shape)
    return ix, iy

def params(fstop):
    strength = min(1, max(0, (4 - math.log2(fstop)) / (4 - math.log2(1.4))))
    return dict(R=0.004 + 0.030 * strength, dof=0.04 + 0.10 * (1 - strength), slope=0.5)

def tex_u8(ctx, a, comps):
    t = ctx.texture((a.shape[1], a.shape[0]), comps, np.ascontiguousarray(a).tobytes(), dtype='f1')
    t.filter = (moderngl.LINEAR, moderngl.LINEAR); t.repeat_x = t.repeat_y = False
    return t

def run(model, rgb_up, outW, outH, fstop=2.8, rot=0, bloom=0.0, focus=None, S=252, tap=None, mode_yuv=True, depth=None, grow=True, tau=0.035):
    ctx = moderngl.create_standalone_context(backend='egl')
    vs = shader('VERTEX')
    P = {n: ctx.program(vertex_shader=vs, fragment_shader=shader(n)) for n in ('INGEST', 'COC', 'GATHER')}
    vao = {n: ctx.vertex_array(p, []) for n, p in P.items()}
    h, w = rgb_up.shape[:2]
    # ---- build a storage-orientation YUV frame (BT.709 limited) from the upright RGB, rotated back by `rot`
    stored = np.rot90(rgb_up, k=rot) if rot else rgb_up      # rot quarter-turns CCW stored -> CW needed to restore
    f = stored.astype(np.float32) / 255
    Y = 16 + 219 * (0.2126 * f[..., 0] + 0.7152 * f[..., 1] + 0.0722 * f[..., 2])
    Cb = 128 + 224 * ((f[..., 2] - (Y - 16) / 219) / 1.8556)
    Cr = 128 + 224 * ((f[..., 0] - (Y - 16) / 219) / 1.5748)
    sh, sw = Y.shape
    Yp = np.clip(Y, 0, 255).astype(np.uint8)
    Up = np.clip(cv2.resize(Cb, (sw // 2, sh // 2), interpolation=cv2.INTER_AREA), 0, 255).astype(np.uint8)
    Vp = np.clip(cv2.resize(Cr, (sw // 2, sh // 2), interpolation=cv2.INTER_AREA), 0, 255).astype(np.uint8)
    # ---- depth
    if depth is None:
        sess = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
        D = sess.run(None, {sess.get_inputs()[0].name: depth_input(rgb_up, S)})[0][0]
        d = normalize(D)
    else: d = depth
    fd, _ = auto_focus(d) if tap is None else (float(np.median(d[int(tap[1]*d.shape[0])-2:int(tap[1]*d.shape[0])+3, int(tap[0]*d.shape[1])-2:int(tap[0]*d.shape[1])+3])), 0)
    if grow:
        if tap is None: seed = auto_seed(d, fd)
        else: seed = (int(tap[0]*d.shape[1]), int(tap[1]*d.shape[0]))
        m, fm = grow_subject(d, seed, fd, tau=tau)
        d = np.where(m, fm, d).astype(np.float32); fd = fm
        run.last_mask = m
    pr = params(fstop)
    # ---- GL resources
    tY, tU, tV = tex_u8(ctx, Yp, 1), tex_u8(ctx, Up, 1), tex_u8(ctx, Vp, 1)
    rgbTex = ctx.texture((outW, outH), 4, dtype='f1'); rgbTex.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR); rgbTex.repeat_x = rgbTex.repeat_y = False
    cocTex = ctx.texture((outW, outH), 1, dtype='f1'); cocTex.filter = (moderngl.LINEAR, moderngl.LINEAR); cocTex.repeat_x = cocTex.repeat_y = False
    outTex = ctx.texture((outW, outH), 4, dtype='f1')
    dA = ctx.texture((d.shape[1], d.shape[0]), 1, d.tobytes(), dtype='f4'); dA.filter = (moderngl.NEAREST, moderngl.NEAREST)
    def fbo(t): return ctx.framebuffer(color_attachments=[t])
    # pass 0: ingest
    fbo(rgbTex).use(); ctx.viewport = (0, 0, outW, outH)
    p = P['INGEST']; p['uFlip'] = -1.0; p['uMode'] = 0; p['uRot'] = rot
    tY.use(0); tU.use(1); tV.use(2); p['uY'] = 0; p['uU'] = 1; p['uV'] = 2; p['uRgb'] = 3
    p['uYuvA'] = (16 / 255, 255 / 219, 255 / 224, 0); p['uYuvB'] = (1.5748, 0.1873, 0.4681, 1.8556)
    vao['INGEST'].render(moderngl.TRIANGLE_STRIP, vertices=4)
    rgbTex.build_mipmaps()
    # pass 1: coc
    fbo(cocTex).use()
    p = P['COC']; p['uFlip'] = -1.0
    rgbTex.use(0); dA.use(1); p['uRgb'] = 0; p['uDepthA'] = 1; p['uDepthB'] = 1
    p['uDepthSize'] = (d.shape[1], d.shape[0]); p['uMix'] = 0.0; p['uFocus'] = fd
    p['uDof'] = pr['dof']; p['uSlope'] = pr['slope']
    p['uLod'] = max(0.0, math.log2(outH / d.shape[0])); p['uSigma'] = 0.07
    vao['COC'].render(moderngl.TRIANGLE_STRIP, vertices=4)
    # pass 2: gather
    fbo(outTex).use()
    p = P['GATHER']; p['uFlip'] = -1.0
    rgbTex.use(0); cocTex.use(1); p['uRgb'] = 0; p['uCoc'] = 1
    p['uAspect'] = outH / outW; p['uRmax'] = pr['R']; p['uHeightPx'] = float(outH); p['uBloom'] = bloom
    vao['GATHER'].render(moderngl.TRIANGLE_STRIP, vertices=4)
    out = np.frombuffer(outTex.read(), np.uint8).reshape(outH, outW, 4)[..., :3]
    ingest = np.frombuffer(rgbTex.read(), np.uint8).reshape(outH, outW, 4)[..., :3]
    coc = np.frombuffer(cocTex.read(), np.uint8).reshape(outH, outW)
    return out, ingest, coc, d, fd

if __name__ == '__main__':
    model = sys.argv[1]; pre = sys.argv[2] if len(sys.argv) > 2 else '/tmp/dtest/out'
    img = data.astronaut()
    out, ing, coc, d, fd = run(model, img, 512, 512)
    cv2.imwrite(pre + '_blur.png', out[..., ::-1]); cv2.imwrite(pre + '_ingest.png', ing[..., ::-1])
    cv2.imwrite(pre + '_coc.png', coc); cv2.imwrite(pre + '_depth.png', (d * 255).astype(np.uint8))
    print('focus', fd, 'ingest max abs err vs source: %.1f' % np.abs(ing.astype(int) - img.astype(int)).mean())
