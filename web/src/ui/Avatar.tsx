// Аватарка: фото (data-URL) или буква ника на фирменном градиенте.
import { IcUser } from './icons'

export function Avatar({ src, nickname, size }: { src?: string; nickname: string; size: number }) {
  const initial = nickname.trim()[0]?.toUpperCase()
  return (
    <span style={{ width: size, height: size, borderRadius: size / 2, flex: 'none', overflow: 'hidden', display: 'grid', placeItems: 'center',
      fontWeight: 800, fontSize: size * 0.42, color: 'var(--on-brand)', background: 'linear-gradient(135deg, var(--brand-from), var(--secondary))' }}>
      {src ? <img src={src} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : initial ?? <IcUser size={size * 0.48} />}
    </span>
  )
}

/** Квадрат 128 px по центру фото → JPEG data-URL (~6 КБ): хватает для кружков и не раздувает лидерборд. */
export async function avatarFromFile(file: File): Promise<string> {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' } as ImageBitmapOptions)
  const side = Math.min(bmp.width, bmp.height)
  const c = document.createElement('canvas')
  c.width = c.height = 128
  c.getContext('2d')!.drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, 128, 128)
  return c.toDataURL('image/jpeg', 0.75)
}
