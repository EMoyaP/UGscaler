"""Export the official CodeFormer checkpoint to a mobile ONNX graph.

Usage:
    python tools/export_codeformer_onnx.py CODEFORMER_REPO CHECKPOINT OUTPUT

The fidelity weight is baked at 0.9 so the Android inference API only needs
the aligned 512x512 RGB face tensor.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import torch
from torch import nn
from torch.nn import functional as F


class MobileCodeFormer(nn.Module):
    def __init__(self, model: nn.Module, fidelity: float) -> None:
        super().__init__()
        self.model = model
        self.fidelity = fidelity

    def forward(self, face: torch.Tensor) -> torch.Tensor:
        encoder_connections = {5: "256", 8: "128", 11: "64", 14: "32"}
        generator_connections = {9: "32", 12: "64", 15: "128", 18: "256"}
        features = {}
        value = face
        for index, block in enumerate(self.model.encoder.blocks):
            value = block(value)
            if index in encoder_connections:
                features[encoder_connections[index]] = value.clone()

        position = self.model.position_emb.unsqueeze(1).repeat(1, value.shape[0], 1)
        query = self.model.feat_emb(value.flatten(2).permute(2, 0, 1))
        for layer in self.model.ft_layers:
            query = layer(query, query_pos=position)
        logits = self.model.idx_pred_layer(query).permute(1, 0, 2)
        top_index = torch.topk(F.softmax(logits, dim=2), 1, dim=2)[1]
        value = self.model.quantize.get_codebook_feat(
            top_index, shape=[face.shape[0], 16, 16, 256]
        )

        for index, block in enumerate(self.model.generator.blocks):
            value = block(value)
            if index in generator_connections:
                key = generator_connections[index]
                value = self.model.fuse_convs_dict[key](
                    features[key].detach(), value, self.fidelity
                )
        return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--fidelity", type=float, default=0.9)
    args = parser.parse_args()

    sys.path.insert(0, str(args.source.resolve()))
    from basicsr.archs.codeformer_arch import CodeFormer

    model = CodeFormer(
        dim_embd=512,
        codebook_size=1024,
        n_head=8,
        n_layers=9,
        connect_list=["32", "64", "128", "256"],
    )
    weights = torch.load(args.checkpoint, map_location="cpu")["params_ema"]
    model.load_state_dict(weights, strict=True)
    wrapped = MobileCodeFormer(model.eval(), args.fidelity).eval()
    sample = torch.zeros(1, 3, 512, 512, dtype=torch.float32)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(
            wrapped,
            sample,
            str(args.output),
            input_names=["face"],
            output_names=["restored"],
            opset_version=17,
            do_constant_folding=True,
        )
    print(f"Exported {args.output} ({args.output.stat().st_size / 1024 / 1024:.1f} MiB)")


if __name__ == "__main__":
    main()
