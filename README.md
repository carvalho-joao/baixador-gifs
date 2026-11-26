# Baixador de GIFs

App Android para salvar GIFs e vídeos de posts do Twitter / X direto na galeria.

- Cole o link do post, ou use **Compartilhar → Baixar GIF** no app do X.
- **Salvar MP4**: o arquivo original, leve, funciona no WhatsApp.
- **Converter para .gif**: gera um .gif de verdade no próprio celular.
- Os arquivos vão para *Filmes/GIFs Twitter* e *Imagens/GIFs Twitter*.

## Baixar o APK

Vá em **Releases** (coluna da direita do repositório) e baixe o `BaixadorGifs.apk` da versão mais recente.
No celular, permita "instalar apps desconhecidos" quando o Android pedir.

## Como é compilado

Cada `push` na branch `main` roda o workflow `.github/workflows/build.yml`, que compila o APK
e publica uma nova Release.

Assinatura: se os secrets `KEYSTORE_BASE64` e `KEYSTORE_PASSWORD` existirem
(Settings → Secrets and variables → Actions), o APK é assinado com essa chave, e as atualizações
instalam por cima da versão anterior. Sem eles, sai assinado com uma chave de debug temporária.

## Estrutura

- `app/src/main/assets/index.html`: a interface (HTML/JS).
- `app/src/main/assets/gifenc.js`: codificador de GIF ([gifenc](https://github.com/mattdesl/gifenc), MIT).
- `app/src/main/java/.../MainActivity.java`: WebView + ponte nativa (busca do post, download, galeria, compartilhar).

Os dados dos posts vêm da API pública do [FxTwitter](https://github.com/FixTweet/FxTwitter).
Só funciona com posts públicos. Respeite os direitos de quem criou o conteúdo.
Fonte Archivo: SIL Open Font License 1.1.
