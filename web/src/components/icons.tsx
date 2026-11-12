import type { SVGProps } from "react";

type IconProps = Omit<SVGProps<SVGSVGElement>, "ref"> & { size?: number | string; strokeWidth?: number | string };

/**
 * NutriMe's own icons, on Lucide's 24×24 stroked grid, with the same props as lucide-react icons.
 * The Android app has the same shapes in NutriIcons.kt.
 */
function make(name: string, paths: string[]) {
  function Icon({ size = 24, strokeWidth = 2, className, ...rest }: IconProps) {
    return (
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`lucide nutri-${name}${className ? ` ${className}` : ""}`}
        aria-hidden="true"
        {...rest}
      >
        {paths.map((d) => (
          <path key={d} d={d} />
        ))}
      </svg>
    );
  }
  Icon.displayName = name;
  return Icon;
}

/** The assistant: an apple that is also a chat bubble, with a leaf. */
export const AssistantIcon = make("assistant", [
  "M12 7.4c1.2-.9 2.5-1.4 3.9-1.4 2.9 0 4.6 2.5 4.6 5.8 0 4.7-3.6 8.7-8.5 8.7-1.5 0-2.9-.4-4.1-1L4 20.6l1-3.6C4 15.6 3.5 13.8 3.5 11.8 3.5 8.5 5.2 6 8.1 6c1.4 0 2.7.5 3.9 1.4Z",
  "M12 7.4V5.5",
  "M12 5.5c0-1.9 1.4-3.2 3.5-3.2 0 1.9-1.4 3.2-3.5 3.2Z",
]);

/** Recipes: a mixing bowl with a spoon. */
export const RecipeIcon = make("recipe", [
  "M3.5 12h17a8.5 8.5 0 0 1-17 0Z",
  "M13.5 12l4.6-6.6",
  "M18.1 5.4c-.6-.9-.3-2.2.7-2.8s2.2-.4 2.7.5c.6.9.3 2.2-.7 2.8s-2.2.4-2.7-.5Z",
]);
