import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

// shadcn 组件统一通过此函数合并条件类名，避免 Tailwind 工具类冲突。
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
