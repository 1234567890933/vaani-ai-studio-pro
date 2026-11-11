#!/usr/bin/env python3
'''
Reference implementation of the app's translation stage, for a PC.

It uses exactly the same tensor names, tokenisation and language ids as
app/src/main/java/ai/hindidub/engine/TranslationEngine.kt, so if a model works here it will
work in the app, and if it does not, the app will not either.

    pip install onnxruntime sentencepiece numpy
    python tools/reference_mt.py --model-dir ~/models/nllb --text "Where are you going?"

Expected model dir layout (what the app downloads):
    encoder.onnx                 NLLB encoder, INT8
    decoder.onnx                 NLLB decoder without past KV cache
    sentencepiece.bpe.model      tokenizer
    tokenizer.json               used only to read the language-token ids

Memory: loading both sessions at once needs >2 GB of free RAM. This script therefore does
what the app does — encode first, free the encoder, then load the decoder.
'''
import argparse
import json
import os
import sys

import numpy as np
import onnxruntime as ort
import sentencepiece as spm

EOS = 2
MAX_NEW = 160
FALLBACK = {"eng_Latn": 256047, "hin_Deva": 256068}
TAGS = {
    "en": "eng_Latn", "hi": "hin_Deva", "bn": "ben_Beng", "ta": "tam_Taml",
    "te": "tel_Telu", "mr": "mar_Deva", "gu": "guj_Gujr", "kn": "kan_Knda",
    "ml": "mal_Mlym", "pa": "pan_Guru", "or": "ory_Orya", "ur": "urd_Arab",
}


def session(path):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    return ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--text", action="append", default=[])
    ap.add_argument("--src", default="en")
    ap.add_argument("--tgt", default="hi")
    args = ap.parse_args()

    d = args.model_dir
    for f in ("encoder.onnx", "decoder.onnx", "sentencepiece.bpe.model"):
        if not os.path.exists(os.path.join(d, f)):
            sys.exit(f"missing {f} in {d}")

    sp = spm.SentencePieceProcessor(model_file=os.path.join(d, "sentencepiece.bpe.model"))
    ids = dict(FALLBACK)
    tj = os.path.join(d, "tokenizer.json")
    if os.path.exists(tj):
        for t in json.load(open(tj)).get("added_tokens", []):
            ids[t["content"]] = t["id"]

    src_tag = TAGS.get(args.src, args.src)
    tgt_tag = TAGS.get(args.tgt, args.tgt)
    print(f"lang ids: {src_tag}={ids.get(src_tag)} {tgt_tag}={ids.get(tgt_tag)}")

    texts = args.text or ["Where are you going tonight?"]

    # ---- encoder pass -----------------------------------------------------
    enc = session(os.path.join(d, "encoder.onnx"))
    print("encoder in:", [i.name for i in enc.get_inputs()],
          "out:", [o.name for o in enc.get_outputs()])
    hiddens = []
    for t in texts:
        seq = [ids[src_tag]] + sp.encode(t) + [EOS]
        x = np.array([seq], dtype=np.int64)
        m = np.ones_like(x)
        hiddens.append((enc.run(None, {"input_ids": x, "attention_mask": m})[0], m))
    del enc

    # ---- decoder pass -----------------------------------------------------
    dec = session(os.path.join(d, "decoder.onnx"))
    print("decoder in:", [i.name for i in dec.get_inputs()],
          "out:", [o.name for o in dec.get_outputs()][:2])
    for text, (h, m) in zip(texts, hiddens):
        out = [ids[tgt_tag]]
        for _ in range(MAX_NEW):
            y = np.array([out], dtype=np.int64)
            logits = dec.run(["logits"], {
                "input_ids": y,
                "encoder_attention_mask": m,
                "encoder_hidden_states": h,
            })[0]
            nxt = int(np.argmax(logits[0, -1]))
            if nxt == EOS:
                break
            out.append(nxt)
        print(f"\n{text}\n  -> {sp.decode(out[1:])}")


if __name__ == "__main__":
    main()
