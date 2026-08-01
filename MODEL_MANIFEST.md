# Manifiesto del modelo opcional

UGscaler 1.4.2 descarga CodeFormer desde el recurso de modelo publicado con
la versión 1.4.0 solo cuando detecta un rostro y la opción
`Rostro IA` está activa. Las fotos y los videos nunca se envían: la descarga
contiene únicamente el modelo y la inferencia posterior se ejecuta con ONNX
Runtime dentro del teléfono.

- Archivo: `codeformer-w09.onnx`
- Fuente: checkpoint oficial CodeFormer `v0.1.0`
- Conversión: `tools/export_codeformer_onnx.py`
- Entrada y salida: RGB, `1 x 3 x 512 x 512`, valores `[-1, 1]`
- Fidelity weight integrado: `0.9`
- Tamaño: 376.799.500 bytes
- SHA-256: `abc9336c5b28b608c258a54813fb59054e5f6986446b54776349ea5f5e23e10e`
- URL: `https://github.com/EMoyaP/UGscaler/releases/download/v1.4.0/codeformer-w09.onnx`

El fichero se acepta únicamente si coincide el tamaño mínimo y el SHA-256.
